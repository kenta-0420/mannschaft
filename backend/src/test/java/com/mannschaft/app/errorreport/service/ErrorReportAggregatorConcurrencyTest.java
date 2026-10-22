package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** AC4: 実Caffeineで初回判定・追加・drainの原子性を検査する。TTLと容量追出しの前に完結する。 */
class ErrorReportAggregatorConcurrencyTest {
    @Test
    void AC4_同じhashへの同時初回はFIRSTが一件で上限を越えない() throws Exception {
        var buffer = new ErrorReportAggregator(32);
        for (int round = 0; round < 30; round++) {
            String key = "r1-" + round;
            var start = new CountDownLatch(1);
            var outcomes = new ArrayList<java.util.concurrent.Future<ErrorReportAggregator.AggregationResult>>();
            try (var pool = Executors.newFixedThreadPool(48)) {
                for (int i = 0; i < 48; i++) outcomes.add(pool.submit(() -> {
                    start.await(); return buffer.addOccurrence(key, "試練", ErrorReportSeverity.HIGH);
                }));
                start.countDown();
                var results = new ArrayList<ErrorReportAggregator.AggregationResult>();
                for (var outcome : outcomes) results.add(outcome.get(10, TimeUnit.SECONDS));
                assertThat(results.stream().filter(value -> value == ErrorReportAggregator.AggregationResult.FIRST_OCCURRENCE))
                        .as("初回は一回だけ").hasSize(1);
                assertThat(buffer.drainAndClear().get(key).occurrenceCount()).isEqualTo(32);
            }
        }
    }

    @Test
    void AC4_computeとdrainが並行しても取り出した件数と残件の合計を失わない() throws Exception {
        var buffer = new ErrorReportAggregator(100);
        var start = new CountDownLatch(1);
        AtomicInteger producers = new AtomicInteger(4);
        AtomicLong drained = new AtomicLong();
        try (var pool = Executors.newFixedThreadPool(5)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            jobs.add(pool.submit(() -> {
                try {
                    start.await();
                    while (producers.get() > 0) {
                        drained.addAndGet(total(buffer.drainAndClear()));
                        Thread.yield();
                    }
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }));
            for (int writer = 0; writer < 4; writer++) jobs.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 1000; i++) buffer.addOccurrence("r1-key-" + (i % 64), "試練", ErrorReportSeverity.MEDIUM);
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                finally { producers.decrementAndGet(); }
            }));
            start.countDown();
            for (var job : jobs) job.get(20, TimeUnit.SECONDS);
        }
        drained.addAndGet(total(buffer.drainAndClear()));

        assertThat(drained.get()).as("上限100・64keys・最大63件/keyなので捨てる理由はない").isEqualTo(4000);
        assertThat(buffer.drainAndClear()).isEmpty();
    }

    private static long total(java.util.Map<String, ErrorReportAggregator.AggregatedEntry> snapshot) {
        return snapshot.values().stream().mapToLong(ErrorReportAggregator.AggregatedEntry::occurrenceCount).sum();
    }
}
