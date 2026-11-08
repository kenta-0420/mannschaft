package com.mannschaft.app.auth.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.event.CirculationExportRequestedEvent;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * CMP-260930-2127: 実Spring非同期proxy・実event-pool・実MySQLで二段投入を検出する。
 *
 * <p>MemberTxBoundaryITSupportのdelegating spyによる観測と、AsyncConfigRejectionPolicyTestの実飽和を
 * 必要な2 Beanだけに再用する。spyはlatch・回数・threadを観測して元Beanを呼び、保存結果を偽装しない。
 * MySQLは基底のJUnit所有singleton、Valkeyは基底既存MockitoBean。共有devのDB・Valkeyへ接続しない。
 * 本クラス自身に@Transactionalを付けず、元イベントを実commitしてAFTER_COMMITを通す。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(AuditLogSingleAsyncBoundaryIT.OwnedContext.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("CMP-260930-2127 監査listenerの単一非同期境界（実Spring・実DB）")
class AuditLogSingleAsyncBoundaryIT extends AbstractMySqlIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final String IP = "192.0.2.27";
    private static final String USER_AGENT = "CMP2127";
    private static final String SESSION_HASH = "a".repeat(64);

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier("event-pool") private Executor executor;
    @PersistenceContext private EntityManager entityManager;
    @MockitoSpyBean private AuditLogEventListener listener;
    @MockitoSpyBean private AuditLogService auditLogService;

    private final AtomicReference<Gate> currentGate = new AtomicReference<>();
    private final AtomicReference<Throwable> blockerFailure = new AtomicReference<>();
    private final AtomicInteger syncCompleted = new AtomicInteger();
    private final AtomicLong listenerThread = new AtomicLong();
    private final AtomicLong syncThread = new AtomicLong();
    private CountDownLatch blockersRelease;
    private Long actorId;

    @BeforeEach
    void prepareOwnedFixtureAndObservation() throws Exception {
        awaitIdle();
        assertThat(AopUtils.isAopProxy(listener)).as("listenerは実Spring proxy").isTrue();
        assertThat(AopUtils.isAopProxy(auditLogService)).as("Serviceも実Spring proxy").isTrue();
        assertThat(pool().getCorePoolSize()).isEqualTo(2);
        assertThat(pool().getMaxPoolSize()).isEqualTo(5);
        assertThat(pool().getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
        actorId = ThreadLocalRandom.current().nextLong(82_127_000_000L, 82_128_000_000L);
        tx().executeWithoutResult(status -> MembershipTestHelper.insertActiveUser(entityManager, actorId));
        blockerFailure.set(null);
        syncCompleted.set(0);
        listenerThread.set(0);
        syncThread.set(0);
        doAnswer(this::observeListener).when(listener).handleLoginSuccess(any(LoginSuccessEvent.class));
        doAnswer(this::observeListener).when(listener)
                .handleCirculationExportRequested(any(CirculationExportRequestedEvent.class));
        doAnswer(invocation -> {
            syncThread.set(Thread.currentThread().getId());
            try {
                return callReal(invocation);
            } finally {
                syncCompleted.incrementAndGet();
            }
        }).when(auditLogService).recordSync(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @AfterEach
    void releaseTasksAndRemoveOnlyOwnedFixture() {
        Gate gate = currentGate.getAndSet(null);
        if (gate != null) gate.proceed.countDown();
        if (blockersRelease != null) blockersRelease.countDown();
        awaitIdle();
        try {
            assertThat(blockerFailure.get()).as("全飽和workerにtimeout・割込みがない").isNull();
        } finally {
            if (actorId != null) {
                jdbc.update("DELETE FROM audit_logs WHERE user_id = ?", actorId);
                jdbc.update("DELETE FROM users WHERE id = ?", actorId);
            }
            blockersRelease = null;
        }
    }

    @AfterAll
    void closeOwnedContextExecutorWithBoundedJoin() throws InterruptedException {
        if (blockersRelease != null) blockersRelease.countDown();
        Gate gate = currentGate.get();
        if (gate != null) gate.proceed.countDown();
        pool().shutdown();
        assertThat(pool().getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS))
                .as("専用spy構成のcontext poolが有限時間内に終了する").isTrue();
    }

    @ParameterizedTest(name = "飽和 [{index}] {0}")
    @EnumSource(Probe.class)
    @DisplayName("AC-1/2 受理済listener内でpoolが満杯でも再投入せず監査を1行保存する")
    void acceptedListenerSavesOnceWithoutSecondSubmission(Probe probe) throws Exception {
        Gate gate = new Gate();
        currentGate.set(gate);
        publishAndCommit(probe.event(actorId));
        assertThat(gate.entered.await(10, TimeUnit.SECONDS)).as("listenerがworker内に到達").isTrue();
        assertThat(listenerThread.get()).isNotEqualTo(Thread.currentThread().getId());
        blockersRelease = new CountDownLatch(1);
        fillRemainingPool(4, blockersRelease);
        try {
            gate.proceed.countDown();
            assertThat(gate.finished.await(10, TimeUnit.SECONDS)).as("受理済listenerが有限時間内に戻る").isTrue();
            assertThat(countRows()).as("二段投入が拒否されて監査が失われてはならない").isEqualTo(1);
            assertThat(syncCompleted.get()).as("実同期保存は1回だけ").isEqualTo(1);
            assertThat(syncThread.get()).as("listenerと同一workerで保存").isEqualTo(listenerThread.get());
            assertPayload(probe);
        } finally {
            gate.proceed.countDown();
            blockersRelease.countDown();
        }
    }

    @ParameterizedTest(name = "正常commit [{index}] {0}")
    @EnumSource(Probe.class)
    @DisplayName("対照 通常commitは元イベントの全引数・null・metadataを1行へ保存する")
    void normalCommitPreservesPayload(Probe probe) throws Exception {
        publishAndCommit(probe.event(actorId));
        Awaitility.await().atMost(WAIT).until(() -> countRows() == 1);
        awaitIdle();
        assertThat(countRows()).isEqualTo(1);
        assertPayload(probe);
    }

    @Test
    @DisplayName("AC-3 対照 入口自体が飽和していれば従来どおり拒否しERRORを記録する")
    void fullPoolStillRejectsAtProxyEntrance() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(com.mannschaft.app.config.AsyncConfig.class);
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.ERROR);
        logger.addAppender(appender);
        blockersRelease = new CountDownLatch(1);
        try {
            fillRemainingPool(5, blockersRelease);
            assertThatThrownBy(() -> listener.handleLoginSuccess(login(USER_AGENT)))
                    .isInstanceOf(TaskRejectedException.class);
            assertThat(listenerThread.get()).isZero();
            assertThat(countRows()).isZero();
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("event-pool 投入拒否");
            });
        } finally {
            blockersRelease.countDown();
            logger.detachAppender(appender);
            logger.setLevel(before);
            appender.stop();
        }
    }

    @Test
    @DisplayName("AC-4 対照 実MySQLの保存失敗は監査ログへ記録され呼出元へ伝播しない")
    void realRepositoryFailureDoesNotEscape() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuditLogService.class);
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.ERROR);
        logger.addAppender(appender);
        try {
            assertThatCode(() -> publishAndCommit(login("x".repeat(501)))).doesNotThrowAnyException();
            Awaitility.await().atMost(WAIT).until(() -> syncCompleted.get() == 1);
            awaitIdle();
            assertThat(countRows()).isZero();
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("監査ログ書き込み失敗");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName())
                        .isEqualTo("org.springframework.dao.DataIntegrityViolationException");
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
            appender.stop();
        }
    }

    @Test
    @DisplayName("AC-5 対照 元TXがrollbackすればAFTER_COMMIT listenerも保存も0回")
    void rolledBackEventDoesNotReachListener() {
        tx().executeWithoutResult(status -> {
            publisher.publishEvent(login(USER_AGENT));
            status.setRollbackOnly();
        });
        awaitIdle();
        assertThat(listenerThread.get()).isZero();
        assertThat(syncCompleted.get()).isZero();
        assertThat(countRows()).isZero();
    }

    private Object observeListener(InvocationOnMock invocation) throws Throwable {
        listenerThread.set(Thread.currentThread().getId());
        Gate gate = currentGate.get();
        if (gate == null) return callReal(invocation);
        gate.entered.countDown();
        try {
            assertThat(gate.proceed.await(30, TimeUnit.SECONDS)).as("試験側は必ずlistenerを解放する").isTrue();
            return callReal(invocation);
        } finally {
            gate.finished.countDown();
        }
    }

    /** MemberTxBoundaryITSupportと同じく、spyの既定Answerから元Beanを呼ぶ。 */
    private static Object callReal(InvocationOnMock invocation) throws Throwable {
        return Mockito.mockingDetails(invocation.getMock()).getMockCreationSettings()
                .getDefaultAnswer().answer(invocation);
    }

    /** 受理済listener以外のworkerとqueueを実サイズのまま埋める。 */
    private void fillRemainingPool(int workers, CountDownLatch release) throws InterruptedException {
        int queueCapacity = pool().getThreadPoolExecutor().getQueue().remainingCapacity();
        assertThat(queueCapacity).isEqualTo(100);
        CountDownLatch started = new CountDownLatch(workers);
        Runnable blocker = () -> {
            started.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    blockerFailure.compareAndSet(null, new AssertionError("飽和workerの解放待ちが上限超過"));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                blockerFailure.compareAndSet(null, new AssertionError("飽和workerが中断された", exception));
            }
        };
        int occupied = pool().getActiveCount();
        int coreWorkers = pool().getCorePoolSize() - occupied;
        assertThat(coreWorkers).isBetween(0, workers);
        for (int index = 0; index < coreWorkers; index++) pool().execute(blocker);
        Awaitility.await().atMost(WAIT).until(() -> pool().getActiveCount() >= occupied + coreWorkers);
        // 以前のcaseで増えた非core workerがidleでもsetupのexecuteを誤拒否させない。
        // 満杯にするのは試験用blockerだけ。listenerの投入は必ず実Spring proxyを通す。
        for (int index = 0; index < queueCapacity; index++) {
            assertThat(pool().getThreadPoolExecutor().getQueue().offer(blocker, 10, TimeUnit.SECONDS))
                    .as("試験用queueの充填が有限時間で完了する").isTrue();
        }
        Awaitility.await().atMost(WAIT).until(() -> pool().getActiveCount() == pool().getPoolSize());
        for (int index = coreWorkers; index < workers; index++) pool().execute(blocker);
        assertThat(started.await(10, TimeUnit.SECONDS)).as("残workerが実際に稼働").isTrue();
        assertThat(pool().getActiveCount()).isEqualTo(5);
        assertThat(pool().getThreadPoolExecutor().getQueue()).hasSize(100);
    }

    private void assertPayload(Probe probe) throws Exception {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM audit_logs WHERE user_id = ?", actorId);
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(actorId);
        assertThat(row.get("target_user_id")).isNull();
        assertThat(row.get("team_id")).isNull();
        assertThat(row.get("organization_id")).isNull();
        assertThat(row.get("created_at")).isNotNull();
        if (probe == Probe.AUTH) {
            assertThat(row.get("event_type")).isEqualTo("LOGIN_SUCCESS");
            assertThat(row.get("ip_address")).isEqualTo(IP);
            assertThat(row.get("user_agent")).isEqualTo(USER_AGENT);
            assertThat(row.get("session_hash")).isEqualTo(SESSION_HASH);
            assertThat(row.get("metadata")).isNull();
        } else {
            assertThat(row.get("event_type")).isEqualTo("CIRCULATION_EXPORT_REQUESTED");
            assertThat(row.get("ip_address")).isNull();
            assertThat(row.get("user_agent")).isNull();
            assertThat(row.get("session_hash")).isNull();
            assertThat(objectMapper.readTree(row.get("metadata").toString()).get("documentId").asLong())
                    .isEqualTo(actorId);
        }
    }

    private long countRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE user_id = ?", Long.class, actorId);
    }

    private LoginSuccessEvent login(String userAgent) {
        return new LoginSuccessEvent(actorId, IP, userAgent, "PASSWORD", SESSION_HASH);
    }

    private void publishAndCommit(Object event) {
        tx().executeWithoutResult(status -> publisher.publishEvent(event));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private ThreadPoolTaskExecutor pool() {
        return (ThreadPoolTaskExecutor) executor;
    }

    private void awaitIdle() {
        Awaitility.await().atMost(WAIT).until(() -> pool().getActiveCount() == 0
                && pool().getThreadPoolExecutor().getQueue().isEmpty());
    }

    private enum Probe {
        AUTH, CIRCULATION;

        Object event(Long userId) {
            return this == AUTH
                    ? new LoginSuccessEvent(userId, IP, USER_AGENT, "PASSWORD", SESSION_HASH)
                    : new CirculationExportRequestedEvent(userId, userId, null, null);
        }
    }

    /** このclassだけのcontextを明示し、終了時に他試験のpoolを閉じない。追加Beanはない。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class OwnedContext {
    }

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
    }
}
