package com.enonic.assignment.reports.fixed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ReportService {

    private static final ReportService INSTANCE = new ReportService();

    private final Map<ReportKey, Report> cache = new ConcurrentHashMap<>();

    private ReportService() {
    }

    public static ReportService getInstance() {
        return INSTANCE;
    }

    public Report getReport(String id) {
        return cache.computeIfAbsent(new ReportKey(id), key -> load(id));
    }

    public Optional<Report> findCached(String id) {
        return Optional.ofNullable(cache.get(new ReportKey(id)));
    }

    public List<String> titles(List<Report> reports) {
        return reports.stream().map(Report::title).filter(Objects::nonNull).toList();
    }

    /**
     * Exports every report and returns when all of them are written.
     *
     * @throws UncheckedIOException if a report could not be written
     */
    public void exportAll(Collection<Report> reports) {
        List<Future<?>> exports = new ArrayList<>();
        // One virtual thread per report: the work is blocking file I/O, so there is no pool to size.
        // The executor belongs to this call. close() waits for the tasks, and the service can be used again.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Report report : reports) {
                exports.add(executor.submit(() -> export(report)));
            }
        }
        exports.forEach(this::await);
    }

    public void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the interrupt for the caller
        } catch (ExecutionException e) {
            // Rethrow what the task threw, not a wrapper around it.
            if (e.getCause() instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private Report load(String id) {
        try {
            String json = Files.readString(file("reports", id, ".json"));
            return ReportParser.parse(json);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read report " + id, e);
        }
    }

    private void export(Report report) {
        try {
            Files.writeString(file("export", report.id(), ".txt"), Objects.requireNonNullElse(report.title(), ""));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot export report " + report.id(), e);
        }
    }

    /**
     * The file of a report inside a directory. Rejects an id such as {@code ../secret} that would
     * point outside it.
     */
    private static Path file(String directory, String id, String extension) {
        Path base = Path.of(directory);
        Path file = base.resolve(id + extension).normalize();
        if (!file.startsWith(base)) {
            throw new IllegalArgumentException("Invalid report id: " + id);
        }
        return file;
    }
}

record Report(String id, String title, List<String> tags, Instant created) {
    Report {
        Objects.requireNonNull(id, "id");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}

// A record has equals and hashCode, so equal keys find the same cache entry.
record ReportKey(String id) {
    ReportKey {
        Objects.requireNonNull(id, "id");
    }
}
