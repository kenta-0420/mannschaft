package com.mannschaft.app.moduleevent;

import com.mannschaft.app.admin.batch.BatchEndpointRegistry;
import com.mannschaft.app.admin.batch.BatchExecutionAspect;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.config.ShedLockConfig;
import com.mannschaft.app.moduleevent.admin.ModuleEventResumeActorStateEntity;
import com.mannschaft.app.moduleevent.admin.ModuleEventResumeAuditEntity;
import com.mannschaft.app.moduleevent.admin.ModuleEventResumeAuditPrivacyService;
import com.mannschaft.app.moduleevent.api.ModuleEventResumeAuditPrivacyApi;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 日次保持jobの先行試練。実M API・実MySQL・実Batch履歴・独立2Providerを通す。
 * schedulingは起動せず、管理registryまたは同一Bean proxyから明示実行する。
 * DB/APIをモックせず、既存ReplayBatchITと同じ観測用AOPで実入口到達を数える。
 * cutoffの閏日・等号・microsecondは既存PrivacyITが担い、ここではjob接続だけを検査する。
 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Isolated("所有監査検体・ShedLock表・job履歴だけを作成して解放する")
class ModuleEventResumeAuditRetentionBatchIT extends AbstractMySqlIntegrationTest {
    @Autowired private ModuleEventResumeAuditPrivacyApi privacyApi;
    @Autowired private ModuleEventResumeAuditPrivacyService privacyService;
    @Autowired private BatchJobLogService jobLogs;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @PersistenceContext private EntityManager entityManager;
    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    private final List<UUID> auditIds = new ArrayList<>();
    private final Set<Long> priorLogIds = new HashSet<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final long actor = (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) | 1L;
    private JdbcTemplate jdbc;
    private String lockTable;
    private String trigger;
    private UUID stateId;
    private boolean lockTableCreated;
    private boolean logSnapshotCaptured;

    @BeforeEach
    void 自分の検体とlock表を用意する() {
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_event_resume_audits", Long.class))
                .as("期限DELETEへ他試験の残骸を巻き込まない").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_event_resume_actor_states WHERE actor_user_id = ?",
                Long.class, actor)).isZero();
        priorLogIds.addAll(logIds());
        logSnapshotCaptured = true;
        lockTable = "m_retention_lock_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TABLE " + lockTable + " (name VARCHAR(64) NOT NULL PRIMARY KEY, "
                + "lock_until TIMESTAMP(3) NOT NULL, locked_at TIMESTAMP(3) NOT NULL, locked_by VARCHAR(255) NOT NULL)");
        lockTableCreated = true;
        stateId = new TransactionTemplate(transactions).execute(status -> {
            var state = ModuleEventResumeActorStateEntity.builder().actorUserId(actor).purged(true).build();
            entityManager.persist(state);
            entityManager.flush();
            return state.getId();
        });
    }

    @AfterEach
    void 所有検体だけを解放する() {
        try {
            contexts.forEach(AnnotationConfigApplicationContext::close);
        } finally {
            try {
                if (trigger != null) jdbc.execute("DROP TRIGGER " + trigger);
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    auditIds.forEach(id -> entityManager.createQuery("DELETE FROM ModuleEventResumeAuditEntity a WHERE a.id = :id")
                            .setParameter("id", id).executeUpdate());
                    if (stateId != null) entityManager.createQuery("DELETE FROM ModuleEventResumeActorStateEntity s "
                                    + "WHERE s.id = :id AND s.actorUserId = :actor")
                            .setParameter("id", stateId).setParameter("actor", actor).executeUpdate();
                });
                if (logSnapshotCaptured) {
                    for (Long id : logIds()) {
                        if (!priorLogIds.contains(id)) jdbc.update("DELETE FROM batch_job_logs WHERE id = ? AND job_name = ?",
                                id, ModuleEventResumeAuditRetentionBatch.BATCH_NAME);
                    }
                }
            } finally {
                if (lockTableCreated) jdbc.execute("DROP TABLE " + lockTable);
            }
        }
    }

    @Test
    void 空の監査も実M入口を一度通り0件成功とfence保持を記録する() {
        var batch = harness(observe(null, null));
        assertThat(batch.batch().deleteExpiredAudits()).isZero();
        assertThat(calls).hasValue(1);
        assertLog("SUCCESS", 0);
        assertFence();
    }

    @Test
    void 管理registryの同一入口から期限超過だけ削除し件数とstate不変を記録する() {
        Instant now = Instant.now();
        UUID expired = audit(now.atZone(ZoneOffset.UTC).minusYears(3).toInstant());
        UUID retained = audit(now.atZone(ZoneOffset.UTC).minusYears(1).toInstant());
        var batch = harness(observe(null, null));
        var descriptor = batch.registry().find(ModuleEventResumeAuditRetentionBatch.BATCH_NAME).orElseThrow();
        assertThat(descriptor.method().getName()).isEqualTo("deleteExpiredAudits");
        assertThat(descriptor.schedulerLockName()).isEqualTo(ModuleEventResumeAuditRetentionBatch.LOCK_NAME);
        batch.registry().invoke(ModuleEventResumeAuditRetentionBatch.BATCH_NAME);
        assertThat(calls).hasValue(1);
        assertThat(auditCount(expired)).isZero();
        assertThat(auditCount(retained)).isEqualTo(1);
        assertLog("SUCCESS", 1);
        assertFence();
    }

    @Test
    void 独立二Providerの同時起動は同じDBlockで一方だけ削除と履歴を実行する() throws Exception {
        UUID expired = audit(Instant.now().atZone(ZoneOffset.UTC).minusYears(3).toInstant());
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Void> returned = new CompletableFuture<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var delegate = observe(entered, release);
        var first = harness(delegate);
        var second = harness(delegate);
        var worker = Executors.newSingleThreadExecutor();
        var running = worker.submit(() -> {
            try { first.batch().deleteExpiredAudits(); }
            catch (Throwable ex) { failure.set(ex); } // 非同期例外は完了後に必ずassertする。
            finally { returned.complete(null); }
        });
        try {
            CompletableFuture.anyOf(entered, returned).get(10, TimeUnit.SECONDS);
            assertThat(calls).as("未接続bodyはtimeoutでなく実入口未到達として検出する: %s", failure.get()).hasValue(1);
            assertThat(entered).isCompleted();
            second.registry().invoke(ModuleEventResumeAuditRetentionBatch.BATCH_NAME);
            assertThat(calls).hasValue(1);
            assertThat(running).isNotDone();
        } finally {
            release.countDown();
            try { running.get(20, TimeUnit.SECONDS); }
            finally {
                worker.shutdownNow();
                assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(failure.get()).isNull();
        assertThat(auditCount(expired)).isZero();
        assertLog("SUCCESS", 1);
        assertFence();
    }

    @Test
    void 実DELETEの障害を返しFAILED履歴と監査rollbackとfence保持を残す() {
        UUID expired = audit(Instant.now().atZone(ZoneOffset.UTC).minusYears(3).toInstant());
        String ownedTrigger = "m_retention_fail_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + ownedTrigger + " BEFORE DELETE ON module_event_resume_audits "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'retention fixture failure'");
        trigger = ownedTrigger;
        var batch = harness(observe(null, null));
        assertThatThrownBy(() -> batch.registry().invoke(ModuleEventResumeAuditRetentionBatch.BATCH_NAME))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(calls).hasValue(1);
        assertThat(auditCount(expired)).isEqualTo(1);
        assertLog("FAILED", 0);
        assertThat(jdbc.queryForObject("SELECT error_message FROM batch_job_logs WHERE job_name = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, ModuleEventResumeAuditRetentionBatch.BATCH_NAME))
                .contains("retention fixture failure");
        assertFence();
    }

    /**
     * 本体と同じDELETEのJPA→JDBC取消経路を短い期限で実証するcharacterization test。
     * 本番methodの300秒そのものの計測ではない。実targetを短いテストTX内で呼び、
     * Service proxyのREQUIRES_NEWによる本番timeoutへの置換だけを避ける。
     */
    @Test
    void 行ロック中の実DELETEは短いTX期限で取り消されrollbackと接続解放を完了する() throws Exception {
        Instant now = Instant.now();
        UUID expired = audit(now.atZone(ZoneOffset.UTC).minusYears(3).toInstant());
        UUID retained = audit(now.atZone(ZoneOffset.UTC).minusYears(1).toInstant());
        var target = AopTestUtils.<ModuleEventResumeAuditPrivacyService>getUltimateTargetObject(privacyService);
        var shortTransaction = new TransactionTemplate(transactions);
        shortTransaction.setTimeout(2);
        CountDownLatch locked = new CountDownLatch(1);
        AtomicBoolean deleteEntered = new AtomicBoolean();
        AtomicReference<Connection> transactionConnection = new AtomicReference<>();
        var worker = Executors.newSingleThreadExecutor();
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT id FROM module_event_resume_audits WHERE id = ? FOR UPDATE")) {
                statement.setQueryTimeout(5);
                statement.setBytes(1, bytes(expired));
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
                locked.countDown();
                var running = worker.submit(() -> {
                    assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                    return catchThrowable(() -> shortTransaction.executeWithoutResult(status -> {
                        entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
                            transactionConnection.set(connection);
                            try (var setting = connection.createStatement();
                                    var rows = setting.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                                assertThat(rows.next()).isTrue();
                                // poolのsession設定を変更せず、DB側期限が外側waitより長いことを確認する。
                                assertThat(rows.getInt(1)).isGreaterThan(15);
                            }
                        });
                        entityManager.createQuery("UPDATE ModuleEventResumeAuditEntity a SET a.reason = :reason WHERE a.id = :id")
                                .setParameter("reason", "timeout rollback marker").setParameter("id", retained).executeUpdate();
                        deleteEntered.set(true);
                        target.deleteExpired(now);
                    }));
                });
                try {
                    // 正常な専用ローカルDBでの試験期待値。取消通信のhard deadlineではない。
                    Throwable failure = running.get(15, TimeUnit.SECONDS);
                    assertThat(deleteEntered).as("事前UPDATEのtimeoutをDELETE取消の証拠にしない").isTrue();
                    assertThat(failure).as("外側の待機期限やDB lock-waitではなくquery取消の例外を確認する").isNotNull();
                    List<Throwable> causes = new ArrayList<>();
                    for (Throwable cause = failure; cause != null && !causes.contains(cause); cause = cause.getCause()) {
                        causes.add(cause);
                    }
                    assertThat(causes).anyMatch(cause -> cause instanceof SQLTimeoutException
                            || cause instanceof org.hibernate.QueryTimeoutException
                            || cause instanceof jakarta.persistence.QueryTimeoutException
                            || cause.getClass().getName().equals("com.mysql.cj.jdbc.exceptions.MySQLTimeoutException"));
                    assertThat(causes).noneMatch(cause -> cause instanceof SQLException sql && sql.getErrorCode() == 1205);
                    assertThat(transactionConnection.get()).isNotNull();
                    assertThat(transactionConnection.get().isClosed()).as("JPAが取得したJDBC接続をpoolへ返却する").isTrue();
                    assertThat(auditCount(expired)).isEqualTo(1);
                    assertThat(auditCount(retained)).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT reason FROM module_event_resume_audits WHERE id = ?",
                            String.class, bytes(retained))).isEqualTo("[REDACTED]");
                    try (var next = dataSource.getConnection()) {
                        next.setAutoCommit(false);
                        try (var rowLock = next.prepareStatement(
                                "SELECT id FROM module_event_resume_audits WHERE id = ? FOR UPDATE")) {
                            rowLock.setQueryTimeout(5);
                            rowLock.setBytes(1, bytes(retained));
                            try (var rows = rowLock.executeQuery()) {
                                assertThat(rows.next()).as("rollbackしたUPDATEの行ロックを後続接続が取得できる").isTrue();
                            }
                        } finally {
                            next.rollback();
                        }
                    }
                    assertFence();
                } finally {
                    // assert/future timeoutでも所有行ロックを先に解放し、workerを残さない。
                    blocker.rollback();
                }
            } finally {
                blocker.rollback();
            }
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Harness harness(ModuleEventResumeAuditPrivacyApi delegate) {
        LockProvider provider = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).withTableName(lockTable).usingDbTime().build());
        var context = new AnnotationConfigApplicationContext();
        contexts.add(context);
        context.register(LockFixtures.class);
        context.registerBean(LockProvider.class, () -> provider);
        context.registerBean(PlatformTransactionManager.class, () -> transactions);
        context.registerBean(BatchExecutionAspect.class, () -> new BatchExecutionAspect(jobLogs, context));
        context.registerBean(ModuleEventResumeAuditRetentionBatch.class, () -> new ModuleEventResumeAuditRetentionBatch(delegate));
        context.registerBean(BatchEndpointRegistry.class, () -> new BatchEndpointRegistry(context, true));
        context.refresh();
        return new Harness(context.getBean(ModuleEventResumeAuditRetentionBatch.class), context.getBean(BatchEndpointRegistry.class));
    }

    private ModuleEventResumeAuditPrivacyApi observe(CompletableFuture<Void> entered, CountDownLatch release) {
        ProxyFactory proxy = new ProxyFactory(privacyApi);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            if (invocation.getMethod().getName().equals("deleteExpired")) {
                calls.incrementAndGet();
                if (entered != null) entered.complete(null);
                if (release != null && !release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("所有保持jobラッチの解放timeout");
                }
            }
            return invocation.proceed();
        });
        return (ModuleEventResumeAuditPrivacyApi) proxy.getProxy();
    }

    private UUID audit(Instant createdAt) {
        UUID id = new TransactionTemplate(transactions).execute(status -> {
            var audit = ModuleEventResumeAuditEntity.builder().publicationId(UUID.randomUUID())
                    .actorUserId(actor).reason("[REDACTED]").createdAt(createdAt).build();
            entityManager.persist(audit);
            entityManager.flush();
            return audit.getId();
        });
        auditIds.add(id);
        return id;
    }

    private long auditCount(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM module_event_resume_audits WHERE id = ?", Long.class, bytes(id));
    }

    private void assertFence() {
        assertThat(jdbc.queryForObject("SELECT id FROM module_event_resume_actor_states WHERE actor_user_id = ?",
                byte[].class, actor)).isEqualTo(bytes(stateId));
        assertThat(jdbc.queryForObject("SELECT purged FROM module_event_resume_actor_states WHERE actor_user_id = ?",
                Boolean.class, actor)).isTrue();
    }

    private void assertLog(String status, int count) {
        var rows = jdbc.queryForList("SELECT status, processed_count FROM batch_job_logs WHERE job_name = ? "
                + "ORDER BY id", ModuleEventResumeAuditRetentionBatch.BATCH_NAME);
        var ownRows = rows.size() - priorLogIds.size();
        assertThat(ownRows).isEqualTo(1);
        var latest = rows.getLast();
        assertThat(latest.get("status")).isEqualTo(status);
        assertThat(((Number) latest.get("processed_count")).intValue()).isEqualTo(count);
    }

    private List<Long> logIds() {
        return jdbc.queryForList("SELECT id FROM batch_job_logs WHERE job_name = ?", Long.class,
                ModuleEventResumeAuditRetentionBatch.BATCH_NAME);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    /** 同じjobの管理入口と定期入口に使う実proxy。 */
    private record Harness(ModuleEventResumeAuditRetentionBatch batch, BatchEndpointRegistry registry) { }

    /** 実timerを起動せず、productionと同じadvisor順でlockと履歴とTXを組み立てる。 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement
    @EnableSchedulerLock(defaultLockAtMostFor = "PT30M", order = ShedLockConfig.ADVISOR_ORDER)
    private static class LockFixtures { }
}
