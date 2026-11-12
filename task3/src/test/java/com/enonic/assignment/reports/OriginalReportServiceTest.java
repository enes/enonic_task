package com.enonic.assignment.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

/**
 * Shows what goes wrong in the original {@code ReportService}. Each test passes because the bug is
 * there. The same three cases run against the fixed version in {@code fixed.ReportServiceTest}.
 */
class OriginalReportServiceTest {

    /** Finding 7: threads that call getInstance() at the same time get different instances. */
    @Test
    void getInstanceCreatesMoreThanOneInstance() throws Exception {
        Field instanceField = ReportService.class.getDeclaredField("instance");
        instanceField.setAccessible(true);

        int instances = 1;
        for (int round = 0; round < 1_000 && instances == 1; round++) {
            instanceField.set(null, null); // forget the instance, so the next calls are first calls again
            instances = getInstanceFromManyThreads().size();
        }

        assertTrue(instances > 1);
    }

    /** Finding 2: two keys with the same id are equal, but a map does not find one under the other. */
    @Test
    void aMapDoesNotFindAValueUnderAnEqualKey() {
        Map<ReportKey, String> cache = new HashMap<>();
        cache.put(new ReportKey("a"), "report");

        assertEquals(new ReportKey("a"), new ReportKey("a"));
        assertNull(cache.get(new ReportKey("a")));
    }

    /** Finding 3: titles fails as soon as one report has a title. */
    @Test
    void titlesThrowsWhenAReportHasATitle() {
        var titled = new Report("1", "Budget", List.of(), Instant.EPOCH);

        assertThrows(IllegalStateException.class, () -> ReportService.getInstance().titles(List.of(titled)));
    }

    /** Calls getInstance() from 16 threads at the same moment and returns the instances they got. */
    private static Set<ReportService> getInstanceFromManyThreads() throws Exception {
        int threads = 16;
        var start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<ReportService>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return ReportService.getInstance();
                }));
            }
            start.countDown();
            Set<ReportService> instances = new HashSet<>();
            for (Future<ReportService> call : calls) {
                instances.add(call.get());
            }
            return instances;
        }
    }
}
