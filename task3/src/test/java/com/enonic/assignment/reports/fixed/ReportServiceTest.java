package com.enonic.assignment.reports.fixed;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * The same three cases as {@code OriginalReportServiceTest}, against the fixed {@code ReportService}.
 * The finding numbers are those in REVIEW.md.
 */
class ReportServiceTest {

    /** Finding 7: threads that call getInstance() at the same time all get the same instance. */
    @Test
    void getInstanceCreatesOneInstance() throws Exception {
        int instances = getInstanceFromManyThreads().size();

        assertEquals(1, instances);
    }

    /** Finding 2. */
    @Test
    void aMapFindsAValueUnderAnEqualKey() {
        Map<ReportKey, String> cache = new HashMap<>();
        cache.put(new ReportKey("a"), "report");

        assertEquals("report", cache.get(new ReportKey("a")));
    }

    /** Finding 3. */
    @Test
    void titlesReturnsTheTitlesAndSkipsReportsWithoutOne() {
        var titled = new Report("1", "Budget", List.of(), Instant.EPOCH);
        var untitled = new Report("2", null, List.of(), Instant.EPOCH);

        assertEquals(List.of("Budget"), ReportService.getInstance().titles(List.of(titled, untitled)));
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
