package com.mannschaft.app.auth.service;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真の未接続providerを使う。単独では公開Guard/MySQLの受入証拠としない。 */
class UserOperationAdmissionTest {
    private static HikariDataSource pool(int maximum) {
        HikariDataSource pool = new HikariDataSource();
        pool.setMaximumPoolSize(maximum);
        return pool;
    }

    @Test void unknownCapacityFailsClosed() {
        AtomicBoolean called = new AtomicBoolean();
        UserOperationAdmission admission = new UserOperationAdmission(new DriverManagerDataSource(), 4);
        assertThatThrownBy(() -> admission.execute(() -> { called.set(true); return null; }))
                .isInstanceOf(UserOperationAdmission.Rejected.class);
        assertThat(called).isFalse();
    }

    @Test void singleConnectionFailsClosed() {
        try (HikariDataSource pool = pool(1)) {
            AtomicBoolean called = new AtomicBoolean();
            assertThatThrownBy(() -> new UserOperationAdmission(pool, 4).execute(() -> { called.set(true); return null; }))
                    .isInstanceOf(UserOperationAdmission.Rejected.class);
            assertThat(called).isFalse();
            assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"2,4,1", "3,4,1", "4,4,1", "5,4,1", "6,4,2", "10,4,4", "50,4,4", "10,1,1", "10,2,2"})
    void boundedCapacityRejectsWithoutQueueing(int poolMaximum, int serverMaximum, int permitted) throws Exception {
        try (HikariDataSource pool = pool(poolMaximum)) {
            UserOperationAdmission admission = new UserOperationAdmission(pool, serverMaximum);
            var workers = Executors.newFixedThreadPool(permitted + 1);
            CountDownLatch entered = new CountDownLatch(permitted);
            CountDownLatch release = new CountDownLatch(1);
            var holders = new ArrayList<java.util.concurrent.Future<String>>();
            try {
                for (int i = 0; i < permitted; i++) holders.add(workers.submit(() -> admission.execute(() -> {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Admission holder timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError("Interrupted holder"); }
                    return "done";
                })));
                if (!entered.await(5, TimeUnit.SECONDS)) {
                    for (var holder : holders) if (holder.isDone()) {
                        try { holder.get(); }
                        catch (java.util.concurrent.ExecutionException e) {
                            throw new AssertionError("Admission holder early cause=" + e.getCause().getClass().getName());
                        }
                    }
                    throw new AssertionError("Admission holders did not enter");
                }
                AtomicBoolean overflowCalled = new AtomicBoolean();
                var overflow = workers.submit(() -> {
                    assertThatThrownBy(() -> admission.execute(() -> { overflowCalled.set(true); return null; }))
                            .isInstanceOf(UserOperationAdmission.Rejected.class);
                });
                overflow.get(1, TimeUnit.SECONDS);
                assertThat(overflowCalled).isFalse();
                release.countDown();
                for (var holder : holders) assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("done");
                assertThat(admission.execute(() -> "next")).isEqualTo("next");
                assertThat(pool.getHikariPoolMXBean()).isNull();
            } finally { release.countDown(); workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS); }
        }
    }

    @Test void failuresAndErrorsReleaseAdmission() {
        try (HikariDataSource pool = pool(4)) {
            UserOperationAdmission admission = new UserOperationAdmission(pool, 4);
            assertThatThrownBy(() -> admission.execute(() -> { throw new IllegalArgumentException("fixture"); }))
                    .isExactlyInstanceOf(IllegalArgumentException.class);
            assertThat(admission.execute(() -> "after failure")).isEqualTo("after failure");
            assertThatThrownBy(() -> admission.execute(() -> { throw new AssertionError("fixture"); }))
                    .isExactlyInstanceOf(AssertionError.class);
            assertThat(admission.execute(() -> "after error")).isEqualTo("after error");
        }
    }

    @Test void ambientTransactionRejectedBeforeCallback() {
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        try (HikariDataSource pool = pool(4)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            AtomicBoolean called = new AtomicBoolean();
            assertThatThrownBy(() -> new UserOperationAdmission(pool, 4).execute(() -> { called.set(true); return null; }))
                    .isInstanceOf(UserOperationAdmission.Rejected.class);
            assertThat(called).isFalse();
        } finally { TransactionSynchronizationManager.setActualTransactionActive(previous); }
    }

    @Test void recursiveEntryRejectedWithSparePermits() {
        try (HikariDataSource pool = pool(10)) {
            UserOperationAdmission admission = new UserOperationAdmission(pool, 4);
            AtomicBoolean nested = new AtomicBoolean();
            assertThat(admission.execute(() -> {
                assertThatThrownBy(() -> admission.execute(() -> { nested.set(true); return null; }))
                        .isInstanceOf(UserOperationAdmission.Rejected.class);
                return "outer";
            })).isEqualTo("outer");
            assertThat(nested).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({"0", "-1"})
    @org.junit.jupiter.api.DisplayName("非正の設定上限は受付を拒否する")
    void nonPositiveServerMaximumRejected(int maximum) {
        try (HikariDataSource pool = pool(4)) {
            AtomicBoolean called = new AtomicBoolean();
            assertThatThrownBy(() -> new UserOperationAdmission(pool, maximum).execute(() -> {
                called.set(true); return null;
            })).isInstanceOf(UserOperationAdmission.Rejected.class);
            assertThat(called).isFalse();
            assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("null処理を拒否した後も受付枠を使える")
    void nullOperationRejectedWithoutLosingPermit() {
        try (HikariDataSource pool = pool(4)) {
            UserOperationAdmission admission = new UserOperationAdmission(pool, 4);
            assertThatThrownBy(() -> admission.execute(null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(admission.execute(() -> "next")).isEqualTo("next");
            assertThat(pool.getHikariPoolMXBean()).isNull();
        }
    }
}
