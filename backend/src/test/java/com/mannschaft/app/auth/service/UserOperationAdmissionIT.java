package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実provider/auth Runner/TM/MySQLを共有IT contextで検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserOperationAdmissionIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserOperationGuard guard;
    @Autowired private UserOperationRunner runner;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private Long user() {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@admission.invalid")
                .lastName("試験").firstName("本人").displayName("本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }

    private void countBegins(Consumer<AtomicInteger> assertion) {
        assertThat(transactionManager).isInstanceOf(ConfigurableTransactionManager.class);
        ConfigurableTransactionManager manager = (ConfigurableTransactionManager) transactionManager;
        var original = new ArrayList<>(manager.getTransactionExecutionListeners());
        long thread = Thread.currentThread().threadId();
        AtomicInteger count = new AtomicInteger();
        TransactionExecutionListener listener = new TransactionExecutionListener() {
            @Override public void beforeBegin(TransactionExecution execution) {
                if (Thread.currentThread().threadId() == thread) count.incrementAndGet();
            }
        };
        var withObserver = new ArrayList<>(original);
        withObserver.add(listener);
        manager.setTransactionExecutionListeners(withObserver);
        try { assertion.accept(count); }
        finally {
            manager.setTransactionExecutionListeners(original);
            assertThat(manager.getTransactionExecutionListeners()).containsExactlyElementsOf(original);
        }
    }

    private static void rejected(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode().getCode())
                        .isEqualTo("AUTHOPERATION_001"));
    }

    @Test void unknownProviderRejectedBeforeRealRunnerStartsTransaction() {
        UserOperationGuard isolated = new UserOperationGuard(
                new UserOperationAdmission(new DriverManagerDataSource(), 4), runner);
        countBegins(count -> {
            AtomicBoolean callback = new AtomicBoolean();
            rejected(() -> isolated.withActiveUser(Long.MAX_VALUE, () -> { callback.set(true); return null; }));
            assertThat(count).hasValue(0);
            assertThat(callback).isFalse();
        });
    }

    @Test void singleConnectionProviderRejectedBeforeRealRunnerStartsTransaction() {
        try (HikariDataSource fixture = new HikariDataSource()) {
            fixture.setMaximumPoolSize(1);
            UserOperationGuard isolated = new UserOperationGuard(new UserOperationAdmission(fixture, 4), runner);
            countBegins(count -> {
                AtomicBoolean callback = new AtomicBoolean();
                rejected(() -> isolated.withActiveUser(Long.MAX_VALUE, () -> { callback.set(true); return null; }));
                assertThat(count).hasValue(0);
                assertThat(callback).isFalse();
                assertThat(fixture.getHikariPoolMXBean()).isNull();
            });
        }
    }

    @Test void fullAdmissionRejectsWhilePrimaryPoolStillHasRoom() throws Exception {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        int poolMaximum = ((HikariDataSource) dataSource).getMaximumPoolSize();
        assertThat(poolMaximum).isGreaterThanOrEqualTo(4);
        int permitted = Math.min(4, Math.max(1, (poolMaximum - 2) / 2));
        var ids = new ArrayList<Long>();
        for (int i = 0; i <= permitted; i++) ids.add(user());
        var workers = Executors.newFixedThreadPool(permitted + 1);
        CountDownLatch entered = new CountDownLatch(permitted);
        CountDownLatch release = new CountDownLatch(1);
        var holders = new ArrayList<java.util.concurrent.Future<String>>();
        try {
            for (int i = 0; i < permitted; i++) {
                Long id = ids.get(i);
                holders.add(workers.submit(() -> guard.withActiveUser(id, () -> {
                    TransactionTemplate independent = new TransactionTemplate(transactionManager);
                    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    independent.setReadOnly(false);
                    return independent.execute(status -> {
                        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
                        entered.countDown();
                        try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Admission fixture timeout"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError("Interrupted fixture"); }
                        return "committed";
                    });
                })));
            }
            if (!entered.await(10, TimeUnit.SECONDS)) {
                for (var holder : holders) if (holder.isDone()) {
                    try { holder.get(); }
                    catch (java.util.concurrent.ExecutionException e) {
                        throw new AssertionError("Guard holder early cause=" + e.getCause().getClass().getName());
                    }
                }
                throw new AssertionError("Real Runner PRIMARY holders did not enter");
            }
            assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections())
                    .isEqualTo(2 * permitted).isLessThan(poolMaximum);
            AtomicBoolean overflow = new AtomicBoolean();
            var denied = workers.submit(() -> rejected(() -> guard.withActiveUser(ids.get(permitted), () -> {
                overflow.set(true); return null;
            })));
            denied.get(1, TimeUnit.SECONDS);
            assertThat(overflow).isFalse();
            release.countDown();
            for (var holder : holders) assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("committed");
            assertThat(guard.withActiveUser(ids.get(permitted), () -> "next")).isEqualTo("next");
        } finally { release.countDown(); workers.shutdownNow(); workers.awaitTermination(10, TimeUnit.SECONDS); }
    }

    @Test void failedCallbacksReleaseSingletonPermitAfterTransactionCompletion() {
        Long id = user();
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> guard.withActiveUser(id, () -> { throw new IllegalArgumentException("fixture"); }))
                    .isExactlyInstanceOf(IllegalArgumentException.class);
        }
        assertThat(guard.withActiveUser(id, () -> "after rollback")).isEqualTo("after rollback");
    }

    @Test void ambientTransactionRejectedWithoutStartingAnotherTransaction() {
        Long id = user();
        countBegins(count -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            int before = count.get();
            AtomicBoolean callback = new AtomicBoolean();
            rejected(() -> guard.withActiveUser(id, () -> { callback.set(true); return null; }));
            assertThat(count).hasValue(before);
            assertThat(callback).isFalse();
        }));
    }

    @Test void recursivePublicGuardRejectedWithoutAnotherTransaction() {
        Long id = user();
        countBegins(count -> assertThat(guard.withActiveUser(id, () -> {
            int before = count.get();
            AtomicBoolean callback = new AtomicBoolean();
            rejected(() -> guard.withActiveUser(id, () -> { callback.set(true); return null; }));
            assertThat(count).hasValue(before);
            assertThat(callback).isFalse();
            return "outer";
        })).isEqualTo("outer"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("公開入口のnull処理は実RunnerのTX開始前に拒否する")
    void publicNullOperationRejectedBeforeRealRunnerTransaction() {
        try (HikariDataSource fixture = new HikariDataSource()) {
            fixture.setMaximumPoolSize(4);
            UserOperationGuard isolated = new UserOperationGuard(new UserOperationAdmission(fixture, 4), runner);
            countBegins(count -> {
                assertThatThrownBy(() -> isolated.withActiveUser(Long.MAX_VALUE, null))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(count).hasValue(0);
                assertThat(fixture.getHikariPoolMXBean()).isNull();
            });
        }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("公開入口のnull本人はcallbackと実RunnerのTX開始前に拒否する")
    void publicNullUserRejectedBeforeRealRunnerTransaction() {
        try (HikariDataSource fixture = new HikariDataSource()) {
            fixture.setMaximumPoolSize(4);
            UserOperationGuard isolated = new UserOperationGuard(new UserOperationAdmission(fixture, 4), runner);
            countBegins(count -> {
                AtomicBoolean callback = new AtomicBoolean();
                assertThatThrownBy(() -> isolated.withActiveUser(null, () -> { callback.set(true); return null; }))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThat(callback).isFalse();
                assertThat(count).hasValue(0);
                assertThat(fixture.getHikariPoolMXBean()).isNull();
            });
        }
    }
}
