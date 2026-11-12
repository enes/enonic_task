package com.enonic.assignment.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class InMemoryVersionedStoreContentionTest {

    /** Parks the thread that hashes it: a put of this key stops inside the store, holding the write lock. */
    private static final class ParkingKey {
        private final CountDownLatch insideWrite = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public int hashCode() {
            insideWrite.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return 1;
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void readsCompleteWhileAWriterHoldsTheWriteLock() throws Exception {
        var store = new InMemoryVersionedStore<Object, String>();
        store.put("a", "1");
        var parked = new ParkingKey();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var writing = CompletableFuture.supplyAsync(() -> store.put(parked, "x"), pool);
            parked.insideWrite.await();
            try {
                // The writer is stopped in the middle of version 2. Every read must finish and see version 1.
                var reads = CompletableFuture.runAsync(() -> {
                    assertEquals(1, store.currentVersion());
                    assertEquals(Optional.of("1"), store.get("a"));
                    assertEquals(Optional.of("1"), store.getAt("a", 1));
                    assertEquals(Map.of("a", "1"), store.snapshot().asMap());
                    assertEquals(List.of(new Change.Put<>(1L, "a", "1")), store.changesSince(0).toList());
                }, pool);
                reads.get(10, TimeUnit.SECONDS);
            } finally {
                parked.release.countDown();
            }
            assertEquals(2, writing.join());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentRemovesOfOneKeyRecordExactlyOneChange() {
        int removers = 4;
        var store = new InMemoryVersionedStore<String, Integer>();
        var barrier = new CyclicBarrier(removers);
        try (ExecutorService pool = Executors.newFixedThreadPool(removers)) {
            for (int round = 0; round < 5_000; round++) {
                long put = store.put("k", round);
                var removes = IntStream.range(0, removers)
                        .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                            awaitAll(barrier);
                            return store.remove("k");
                        }, pool))
                        .toList();
                // Whoever comes first removes it; for the others it is a no-op at the same version.
                removes.forEach(remove -> assertEquals(put + 1, remove.join()));
                assertEquals(put + 1, store.currentVersion(), "exactly one remove took a version");
            }
        }
    }

    private static void awaitAll(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
