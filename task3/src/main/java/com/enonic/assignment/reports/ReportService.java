package com.enonic.assignment.reports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class ReportService {
    private static ReportService instance;
    private final Map<ReportKey, Report> cache = new HashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static ReportService getInstance() {
        if (instance == null) {
            instance = new ReportService();
        }
        return instance;
    }

    public Report getReport(String id) {
        ReportKey key = new ReportKey(id);
        Report report = cache.get(key);
        if (report == null) {
            report = load(id);
            cache.put(key, report);
        }
        return report;
    }

    public Optional<Report> findCached(String id) {
        try {
            return Optional.of(cache.get(new ReportKey(id)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<String> titles(List<Report> reports) {
        Stream<Report> titled = reports.stream().filter(r -> r.title() != null);
        if (titled.count() == 0) {
            return Collections.emptyList();
        }
        return titled.map(Report::title).toList();
    }

    public void exportAll(Collection<Report> reports) {
        for (Report report : reports) {
            executor.submit(() -> {
                export(report);
                return null;
            });
        }
        executor.shutdown();
    }

    public void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            // nothing to do
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private Report load(String id) {
        try {
            String json = Files.readString(Path.of("reports", id + ".json"));
            return ReportParser.parse(json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void export(Report report) throws IOException {
        Files.writeString(Path.of("export", report.id() + ".txt"), report.title());
    }
}

record Report(String id, String title, List<String> tags, Instant created) {
    Report {
        Objects.requireNonNull(id);
    }
}

final class ReportKey {
    private final String id;

    ReportKey(String id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ReportKey other && other.id.equals(id);
    }
}
