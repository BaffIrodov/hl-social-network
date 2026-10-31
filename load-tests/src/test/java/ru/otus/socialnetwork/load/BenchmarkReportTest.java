package ru.otus.socialnetwork.load;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkReportTest {
    @Test
    void writesOneSelfContainedHtmlAndPreservesSourceMeasurements(@TempDir Path directory) throws Exception {
        List<StageStats> stages = new java.util.ArrayList<>();
        for (int users : new int[] {1, 10, 100, 1000}) {
            StageStats stage = new StageStats(users, 1);
            stage.firstStart.set(1_000_000_000L);
            stage.lastEnd.set(3_000_000_000L);
            stage.ok.add(2);
            stage.latencies.addAll(List.of(10L, 30L));
            stages.add(stage);
        }
        BenchmarkReport.write(directory, stages, "before-index", "http://localhost:8880", "<Ив>", "Ив",
                2, 1, 1, 0, "1000000 / 17 / users_pkey", "Run");
        String html = Files.readString(directory.resolve("latest.html"));
        assertTrue(html.contains("first_name=&lt;Ив&gt;"));
        assertTrue(html.contains("Приложение: Run."));
        assertTrue(html.contains("Highcharts.chart"));
        assertFalse(html.contains("<script src="));
        String chartData = html.substring(html.indexOf("const rows="), html.indexOf("function draw("));
        assertFalse(chartData.contains("NaN"));
        assertFalse(chartData.contains("Infinity"));
        try (var entries = Files.walk(directory)) {
            Path csv = entries.filter(p -> p.getFileName().toString().equals("measurements.csv")).findFirst().orElseThrow();
            assertEquals(5, Files.readAllLines(csv).size());
        }
    }

    @Test
    void combinesRawLatencySamplesAndDividesRequestsByOnlyTheirMeasurementWindows() {
        StageStats first = stage(1, 1, 0, List.of(1L, 2L));
        StageStats second = stage(2, 4, 2, List.of(100L, 200L, 300L, 400L, 500L, 600L));
        var result = BenchmarkReport.summarize(List.of(first, second), 10, 0);
        assertEquals(10, result.total());
        assertEquals(8, result.ok());
        assertEquals(2, result.failed());
        assertEquals(2.0, result.rps(), 0.000001);
        assertEquals(1.6, result.okRps(), 0.000001);
        assertEquals(20.0, result.errorRate(), 0.000001);
        assertEquals(262.875, result.mean(), 0.000001);
        assertEquals(200L, result.percentile(.50));
        assertEquals(600L, result.percentile(.95));
        assertEquals(600L, result.percentile(.99));
    }

    @Test
    void failedRequestsHaveAnErrorRateButNoInventedSuccessfulLatency() {
        var result = BenchmarkReport.summarize(List.of(stage(1, 2, 4, List.of())), 10, 1);
        assertEquals(2.0, result.rps(), 0.000001);
        assertEquals(0.0, result.okRps());
        assertEquals(100.0, result.errorRate());
        assertNull(result.mean());
        assertNull(result.percentile(.95));
    }

    @Test
    void emptyMeasurementDoesNotProduceNaNOrInfiniteRates() {
        var result = BenchmarkReport.summarize(List.of(new StageStats(10, 1)), 10, 1);
        assertEquals(0, result.total());
        assertEquals(0.0, result.seconds());
        assertEquals(0.0, result.rps());
        assertEquals(0.0, result.okRps());
        assertEquals(0.0, result.errorRate());
        assertNull(result.percentile(.99));
    }

    private static StageStats stage(int repeat, int seconds, int failed, List<Long> samples) {
        var stage = new StageStats(10, repeat);
        stage.firstStart.set(1_000_000_000L);
        stage.lastEnd.set(1_000_000_000L + seconds * 1_000_000_000L);
        stage.ok.add(samples.size());
        stage.failed.add(failed);
        stage.latencies.addAll(samples);
        return stage;
    }
}
