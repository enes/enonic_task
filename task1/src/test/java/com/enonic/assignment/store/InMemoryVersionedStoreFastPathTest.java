package com.enonic.assignment.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Aims readers at the two moments the O(1) read path has to handle: a key whose newest change is
 * being written, and a key that is being created. One writer runs while readers repeat a check
 * until it is done.
 */
class InMemoryVersionedStoreFastPathTest {

    private static final int READERS = 4;

    private final InMemoryVersionedStore<String, Long> store = new InMemoryVersionedStore<>();
    private final CountDownLatch start = new CountDownLatch(1);

    /** The writer puts {@code hot} = the version of that put, so a value names the version it was written at. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void keyBeingOverwrittenIsNeitherStaleNorUncommitted() {
        runWhileWriting(
                () -> {
                    for (long i = 1; i <= 200_000; i++) {
                        store.put("hot", i);
                    }
                },
                () -> {
                    long before = store.currentVersion();
                    var value = store.get("hot");
                    var valueAtBefore = store.getAt("hot", before);
                    long after = store.currentVersion();
                    if (before == 0) {
                        return;
                    }
                    assertEquals(Optional.of(before), valueAtBefore, "getAt the version just read");
                    long seen = value.orElseThrow();
                    assertTrue(seen >= before, "stale: read " + seen + " after version " + before + " was committed");
                    assertTrue(seen <= after, "uncommitted: read " + seen + " while the store was at " + after);
                });
    }

    /** The writer creates {@code new-i} at version i + 1, and readers ask for exactly the key in flight. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void keyBeingCreatedIsAbsentOrCommitted() {
        runWhileWriting(
                () -> {
                    for (long i = 0; i < 100_000; i++) {
                        store.put("new-" + i, i);
                    }
                },
                () -> {
                    long version = store.currentVersion();
                    var key = "new-" + version; // written by the next put, which may be under way
                    assertEquals(Optional.empty(), store.getAt(key, version), key + " at version " + version);
                    store.get(key).ifPresent(value -> assertEquals(version, value));
                });
    }

    private void runWhileWriting(Runnable writer, Runnable check) {
        try (ExecutorService pool = Executors.newFixedThreadPool(1 + READERS)) {
            var writing = CompletableFuture.runAsync(() -> {
                awaitStart();
                writer.run();
            }, pool);
            var readers = IntStream.range(0, READERS)
                    .mapToObj(reader -> CompletableFuture.runAsync(() -> {
                        awaitStart();
                        do {
                            check.run();
                        } while (!writing.isDone());
                    }, pool))
                    .toList();

            start.countDown();

            // join() rethrows any exception or failed assertion.
            writing.join();
            readers.forEach(CompletableFuture::join);
        }
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
