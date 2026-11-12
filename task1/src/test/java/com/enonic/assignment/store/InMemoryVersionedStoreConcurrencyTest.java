package com.enonic.assignment.store;

import static java.util.Comparator.comparingLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Writers put and remove their own keys while readers take snapshots, read through them and tail
 * the change log. Afterwards the log is replayed to check what every reader saw.
 */
class InMemoryVersionedStoreConcurrencyTest {

    private static final int WRITERS = 4;
    private static final int READERS = 4;
    private static final int WRITES_PER_WRITER = 20_000;
    private static final int KEYS_PER_WRITER = 20;
    /** A reader keeps a snapshot about every this many versions, so they spread over the whole run. */
    private static final long KEEP_SNAPSHOT_EVERY = 250;
    private static final int MAX_GETS_KEPT_PER_READER = 50_000;

    private final InMemoryVersionedStore<String, Integer> store = new InMemoryVersionedStore<>();
    private final CountDownLatch start = new CountDownLatch(1);

    /** A snapshot and the map a reader got from it while writes were running. */
    private record Seen(Snapshot<String, Integer> snapshot, Map<String, Integer> map) {
    }

    /** A get, with currentVersion() read just before and just after it. */
    private record Get(String key, Optional<Integer> value, long before, long after) {
    }

    private record ReaderResult(List<Seen> snapshots, List<Get> gets) {
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void writersAndReadersKeepTheInvariants() {
        List<List<Long>> writtenVersions;
        List<Seen> seen;
        List<Get> gets;
        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS + READERS)) {
            List<CompletableFuture<List<Long>>> writers = IntStream.range(0, WRITERS)
                    .mapToObj(writer -> CompletableFuture.supplyAsync(() -> write(writer), pool))
                    .toList();
            var writing = CompletableFuture.allOf(writers.toArray(CompletableFuture[]::new));
            List<CompletableFuture<ReaderResult>> readers = IntStream.range(0, READERS)
                    .mapToObj(reader -> CompletableFuture.supplyAsync(() -> read(writing), pool))
                    .toList();

            start.countDown();

            // join() rethrows any exception or failed assertion from a writer or reader.
            writtenVersions = writers.stream().map(CompletableFuture::join).toList();
            var results = readers.stream().map(CompletableFuture::join).toList();
            seen = results.stream().flatMap(result -> result.snapshots().stream()).toList();
            gets = results.stream().flatMap(result -> result.gets().stream()).toList();
        }

        var history = store.changesSince(0).toList();
        long last = store.currentVersion();
        var oneToLast = LongStream.rangeClosed(1, last).boxed().toList();

        assertEquals(oneToLast, history.stream().map(Change::version).toList(), "the log has no gaps or repeats");
        assertEquals(oneToLast, writtenVersions.stream().flatMap(List::stream).sorted().toList(),
                "each version was returned by exactly one write");
        writtenVersions.forEach(InMemoryVersionedStoreConcurrencyTest::assertIncreasing);

        assertTrue(seen.stream().anyMatch(s -> s.snapshot().version() > 0 && s.snapshot().version() < last),
                "no reader took a snapshot while writes were running");

        // Replay the log once, in version order, and compare at each snapshot's version.
        var replayed = new HashMap<String, Integer>();
        int applied = 0;
        for (var s : seen.stream().sorted(comparingLong(s -> s.snapshot().version())).toList()) {
            long version = s.snapshot().version();
            while (applied < version) {
                apply(replayed, history.get(applied++));
            }
            assertEquals(replayed, s.map(), "what a reader saw at version " + version);
            for (var key : allKeys()) {
                assertEquals(Optional.ofNullable(replayed.get(key)), s.snapshot().get(key),
                        "snapshot " + version + " read again after all writes, key " + key);
            }
        }
        while (applied < last) {
            apply(replayed, history.get(applied++));
        }
        assertEquals(replayed, store.snapshot().asMap(), "final state");

        // Every get returned the key's value at some version between the two currentVersion() reads
        // around it: never a stale value, never one that was not committed yet.
        Map<String, NavigableMap<Long, Optional<Integer>>> timelines = new HashMap<>();
        for (var change : history) {
            timelines.computeIfAbsent(change.key(), k -> new TreeMap<>()).put(change.version(), valueOf(change));
        }
        for (var get : gets) {
            var timeline = timelines.getOrDefault(get.key(), Collections.emptyNavigableMap());
            var possible = new ArrayList<Optional<Integer>>();
            var atBefore = timeline.floorEntry(get.before());
            possible.add(atBefore == null ? Optional.empty() : atBefore.getValue());
            possible.addAll(timeline.subMap(get.before(), false, get.after(), true).values());
            assertTrue(possible.contains(get.value()), () -> get + " is not a value of the key in that range");
        }
    }

    /** Returns the versions of this writer's committed writes, in the order it made them. */
    private List<Long> write(int writer) {
        awaitStart();
        var random = new Random(writer);
        var present = new HashSet<String>();
        var versions = new ArrayList<Long>(WRITES_PER_WRITER);
        for (int i = 0; i < WRITES_PER_WRITER; i++) {
            var key = key(writer, random.nextInt(KEYS_PER_WRITER));
            if (random.nextInt(3) == 0) {
                // Only this writer touches its keys, so it knows whether the remove is a no-op.
                long version = store.remove(key);
                if (present.remove(key)) {
                    versions.add(version);
                }
            } else {
                versions.add(store.put(key, i));
                present.add(key);
            }
        }
        return versions;
    }

    /** Reads until all writers are done. Returns some of the snapshots it took and the gets it made. */
    private ReaderResult read(CompletableFuture<Void> writing) {
        awaitStart();
        var kept = new ArrayList<Seen>();
        var gets = new ArrayList<Get>();
        var keys = allKeys();
        long previous = 0;
        long keepNextAt = 0;
        long changesRead = 0;
        int round = 0;
        do {
            var snapshot = store.snapshot();
            long version = snapshot.version();
            assertTrue(version >= previous, "snapshot versions never go back");
            previous = version;

            var map = snapshot.asMap();
            var key = keys.get(round++ % keys.size());
            assertEquals(Optional.ofNullable(map.get(key)), snapshot.get(key), "asMap and get agree");
            assertEquals(Optional.ofNullable(map.get(key)), store.getAt(key, version), "snapshot and getAt agree");
            long before = store.currentVersion();
            var value = store.get(key);
            long after = store.currentVersion();
            if (gets.size() < MAX_GETS_KEPT_PER_READER) {
                gets.add(new Get(key, value, before, after));
            }
            if (version >= keepNextAt) {
                kept.add(new Seen(snapshot, map));
                keepNextAt = version + KEEP_SNAPSHOT_EVERY;
            }

            // Tail the log while writes continue: it has no gaps.
            for (var it = store.changesSince(changesRead).iterator(); it.hasNext(); ) {
                assertEquals(++changesRead, it.next().version(), "changesSince is contiguous");
            }
        } while (!writing.isDone());
        return new ReaderResult(kept, gets);
    }

    private static void apply(Map<String, Integer> state, Change<String, Integer> change) {
        switch (change) {
            case Change.Put<String, Integer>(_, var key, var value) -> state.put(key, value);
            case Change.Remove<String, Integer>(_, var key) -> state.remove(key);
        }
    }

    private static Optional<Integer> valueOf(Change<String, Integer> change) {
        return switch (change) {
            case Change.Put<String, Integer>(_, _, var value) -> Optional.of(value);
            case Change.Remove<String, Integer> _ -> Optional.empty();
        };
    }

    private static void assertIncreasing(List<Long> versions) {
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(versions.get(i - 1) < versions.get(i), "a writer's versions increase");
        }
    }

    private static List<String> allKeys() {
        return IntStream.range(0, WRITERS)
                .boxed()
                .flatMap(writer -> IntStream.range(0, KEYS_PER_WRITER).mapToObj(k -> key(writer, k)))
                .toList();
    }

    private static String key(int writer, int k) {
        return "w" + writer + ":k" + k;
    }

    private void awaitStart() {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
