package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;

/** 合成配送ITだけの有限観測。本文・ID・資格情報・例外本文は受け取らない。 */
final class RanchDeliveryMeasurementCollector implements AutoCloseable {
    enum Case { FOUR_SOURCES, ACK_RECLAIM, DEFER_ADMISSION }
    enum Stage { TRANSPORT, LEASE, CONSUMER, WRITER, ACK, DEFER, RETRY, DRAIN, COMMIT_TO_CONFIRMED_ACK, POOL_PROBE }
    enum Count { ACKED, DECISIONS, REWARD_LEDGER, BALANCE, STALE_ACK_REJECTED, RECLAIMED,
        DEFERRED_WITHOUT_ATTEMPT, OVERFLOW_CALLBACKS, PERMIT_RECOVERED }
    private final HikariDataSource pool;
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicInteger waiters = new AtomicInteger();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    private final Case measurementCase;
    private boolean accepted;
    private boolean warmup;
    private int intentionalPoolTimeouts;

    RanchDeliveryMeasurementCollector(HikariDataSource pool, Case measurementCase) {
        this.pool = pool;
        this.measurementCase = measurementCase;
        sampler.scheduleAtFixedRate(this::gauge, 0, 1, TimeUnit.MILLISECONDS);
    }

    private void gauge() {
        var bean = pool.getHikariPoolMXBean();
        if (bean != null) {
            peak.accumulateAndGet(bean.getActiveConnections(), Math::max);
            waiters.accumulateAndGet(bean.getThreadsAwaitingConnection(), Math::max);
        }
    }

    synchronized void sample(Stage stage, String source, long nanos, boolean failed,
            boolean txActive, boolean readOnly) {
        gauge();
        var value = new LinkedHashMap<String, Object>();
        value.put("stage", stage.name());
        value.put("source", source);
        value.put("elapsedNanos", nanos);
        value.put("failed", failed);
        value.put("warmup", warmup);
        value.put("txActiveAtAdviceEntry", txActive);
        value.put("txReadOnlyAtAdviceEntry", readOnly);
        samples.add(value);
    }

    <T> T time(Stage stage, String source, Supplier<T> operation) {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            T result = operation.get();
            failed = false;
            return result;
        } finally {
            sample(stage, source, System.nanoTime() - start, failed, false, false);
        }
    }

    void warmup(boolean value) { warmup = value; }
    void accepted() { accepted = true; }
    void intentionalPoolTimeout() { intentionalPoolTimeouts++; }
    void count(Count kind, long value) { counts.put(kind.name(), value); }

    synchronized void requireSamples(Stage stage, String source, int expected) {
        assertThat(samples.stream().filter(row -> stage.name().equals(row.get("stage"))
                && source.equals(row.get("source"))).count()).isEqualTo(expected);
    }

    synchronized void requireNoFailedSamples() {
        assertThat(samples).noneMatch(row -> Boolean.TRUE.equals(row.get("failed")));
    }

    void requirePoolBound() {
        assertThat(peak.get()).isBetween(1, 2);
    }

    /** 成功だけの分位にしない。失敗sampleも同じstageの分位へ残す。 */
    private synchronized Map<String, Object> distributions() {
        var output = new LinkedHashMap<String, Object>();
        for (Stage stage : Stage.values()) {
            for (String source : List.of("ALL", "TIMELINE_ORIGINAL", "BLOG_FIRST_PUBLISH",
                    "ATTENDANCE_RESPONSE", "PERSONAL_RECALL_COMPLETE")) {
                var values = samples.stream().filter(row -> stage.name().equals(row.get("stage"))
                                && source.equals(row.get("source")) && !Boolean.TRUE.equals(row.get("warmup")))
                        .map(row -> (Long) row.get("elapsedNanos")).sorted().toList();
                if (!values.isEmpty()) {
                    output.put(stage.name() + "/" + source, Map.of("count", values.size(),
                            "p50Nanos", percentile(values, 50), "p95Nanos", percentile(values, 95),
                            "p99Nanos", percentile(values, 99), "maxNanos", values.getLast()));
                }
            }
        }
        return output;
    }

    private static long percentile(List<Long> values, int percentile) {
        return values.get((int) Math.ceil(values.size() * percentile / 100.0) - 1);
    }

    void write(ObjectMapper json, boolean cleanupConfirmed) throws IOException {
        String head = System.getenv("RANCH_MEASUREMENT_SOURCE_HEAD");
        boolean headProvided = head != null && head.matches("[a-f0-9]{40}");
        var proof = new LinkedHashMap<String, Object>();
        proof.put("sourceHead", headProvided ? head : "NOT_PROVIDED");
        proof.put("fixtureBase", "6d0c29122d6385a92009a5dbd54fecff275a459f");
        proof.put("case", measurementCase.name());
        proof.put("caseAssertionsPassed", accepted);
        proof.put("cleanupConfirmed", cleanupConfirmed);
        proof.put("fullGreen", false);
        proof.put("nativeQualificationProven", false);
        proof.put("realUiProven", false);
        proof.put("productionSloProven", false);
        proof.put("registeredVersion", "TEST_ONLY_NOT_MEASURED");
        proof.put("measuredVersion", null);
        proof.put("adoptedRanges", null);
        proof.put("testInputs", Map.of("batchSize", 10, "leaseSeconds", 30, "maxAttempts", 3,
                "initialBackoffSeconds", 1, "maxBackoffSeconds", 60));
        proof.put("configuredPoolMaximum", pool.getMaximumPoolSize());
        proof.put("sampledPeakConnections", peak.get());
        proof.put("sampledPeakWaiters", waiters.get());
        proof.put("gaugeIntervalMillis", 1);
        proof.put("intentionalThirdConnectionTimeouts", intentionalPoolTimeouts);
        proof.put("connectionIds", "NOT_OBSERVED");
        proof.put("transactionAdvisorOrder", "NOT_OBSERVED");
        proof.put("measurementOverhead", "TEST_ASPECT_AND_1MS_GAUGE");
        proof.put("jvmVersion", Runtime.version().toString());
        proof.put("processors", Runtime.getRuntime().availableProcessors());
        proof.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        proof.put("samples", List.copyOf(samples));
        proof.put("observedCounts", Map.copyOf(counts));
        proof.put("distributions", distributions());
        Path directory = Path.of("build", "reports", "ranch-delivery-measurement");
        Files.createDirectories(directory);
        Path output = directory.resolve(measurementCase.name().toLowerCase() + ".json");
        if (Files.exists(output)) throw new IOException("測定証拠の既存ファイルを上書きできません");
        Files.write(output, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(proof));
    }

    @Override public void close() throws InterruptedException {
        sampler.shutdown();
        if (!sampler.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("観測終了期限切れ");
    }
}
