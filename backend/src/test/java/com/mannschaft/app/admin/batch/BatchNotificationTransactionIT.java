package com.mannschaft.app.admin.batch;

import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.repository.BatchJobLogRepository;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.CommitSaboteurConfig;
import com.mannschaft.app.support.test.CommitSaboteurConfig.CommitSaboteur;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;

/**
 * Issue #2997 / CMP-260827-1152 G7-b（管理バッチ）— バッチ完了・失敗通知の業務TX切り離しの実DB検証。
 *
 * <p>対象: {@link BatchExecutionAspect} が発火する {@code BatchCompletedEvent} / {@code BatchFailedEvent} を受ける
 * {@code BatchEventListener#onCompleted / #onFailed}。{@code @BatchEndpoint} 付きのプローブBeanを本物の Aspect・
 * TX インタセプタ越しに呼び、SYSTEM_ADMIN への通知行を別接続で観測する。</p>
 *
 * <h2>保証範囲（M7）</h2>
 * <p>エンドポイント自身の TX が確定（commit / rollback）した後に必ず通知する。外側の TX から呼ばれた場合は対象外
 * （本システムの呼出元はスケジューラと SystemAdminBatchController→BatchEndpointRegistry で、いずれも外側TXを持たない）。</p>
 *
 * <h2>観測方法と限界</h2>
 * <p>「通知が来ない」ことの確認は sleep ではなく、成功側の待機の印と event-pool の drain を待ってから行う。
 * クラスに {@code @Transactional} を付けない（commit が起きず AFTER_COMMIT が発火しないため）。
 * 通知の配送失敗は {@link NotificationService} の spy が入口で例外を投げる注入であり、実 MySQL 上の永続化失敗ではない。</p>
 */
@Import({CommitSaboteurConfig.class, BatchNotificationTransactionIT.ProbeConfig.class})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G7-b 管理バッチ通知のTX分離（実DB）")
class BatchNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    /** commit 直前の rollback 注入（CommitSaboteur）の対象にするイベント。 */
    record CommitProbeEvent(String tag) {
    }

    /** {@code @BatchEndpoint} 付きのプローブ。名前はテストごとに固有（通知の突き合わせに使う）。 */
    static class BatchProbe {
        // 検体は CGLIB プロキシ越しに使うため、インスタンスフィールドはプロキシ側から見えない（null）。static に置く。
        static final AtomicInteger BODY_CALLS = new AtomicInteger();
        private final ApplicationEventPublisher publisher;

        BatchProbe(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @BatchEndpoint(name = "g7b-it-no-tx")
        public int noTx() {
            BODY_CALLS.incrementAndGet();
            return 5;
        }

        @BatchEndpoint(name = "g7b-it-tx-ok")
        @Transactional
        public int txOk() {
            BODY_CALLS.incrementAndGet();
            return 3;
        }

        @BatchEndpoint(name = "g7b-it-tx-throws")
        @Transactional
        public int txThrows() {
            BODY_CALLS.incrementAndGet();
            throw new IllegalStateException("本体の失敗（検証用）");
        }

        @BatchEndpoint(name = "g7b-it-commit-fails")
        @Transactional
        public int commitFails() {
            BODY_CALLS.incrementAndGet();
            publisher.publishEvent(new CommitProbeEvent("commit-fails"));
            return 9;
        }

        @BatchEndpoint(name = "g7b-it-log-fails")
        public int logFails() {
            BODY_CALLS.incrementAndGet();
            return 1;
        }

        @BatchEndpoint(name = "g7b-it-body-throws-no-tx")
        public int bodyThrowsNoTx() {
            BODY_CALLS.incrementAndGet();
            throw new IllegalStateException("本体の失敗・TXなし（検証用）");
        }

        @BatchEndpoint(name = "g7b-it-per-admin")
        public int perAdmin() {
            BODY_CALLS.incrementAndGet();
            return 1;
        }

        /** 未シードのゲートキーは isEnabled=false（フェイルクローズ）なので常に停止側になる。 */
        @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
                gateKeys = {"g7b_it_unseeded_gate_key"}, reason = "G7-b IT: 停止遷移の検証用")
        @BatchEndpoint(name = "g7b-it-gated")
        public int gated() {
            BODY_CALLS.incrementAndGet();
            return 1;
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        BatchProbe batchProbe(ApplicationEventPublisher publisher) {
            return new BatchProbe(publisher);
        }
    }

    @Autowired private BatchProbe probe;
    @Autowired private BatchJobLogRepository batchJobLogRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private CommitSaboteur saboteur;
    @Autowired @Qualifier("event-pool") private java.util.concurrent.Executor eventPool;

    @MockitoSpyBean private NotificationService notificationService;
    @MockitoSpyBean private BatchJobLogService batchJobLogService;

    @PersistenceContext
    private EntityManager em;

    @AfterEach
    void tearDown() {
        saboteur.disarm();
        BatchProbe.BODY_CALLS.set(0);
    }

    // ═════════════════════════════════════════════════════════════════════
    // M7: エンドポイント自身の TX が確定した後に通知する
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TXなしの本体が成功したら、SYSTEM_ADMIN に BATCH_COMPLETED の通知行が作られる")
    void TXなし成功で成功通知() {
        Long admin = systemAdmin("nt-ok");

        probe.noTx();

        awaitNotification(admin, "BATCH_COMPLETED", "g7b-it-no-tx");
        assertThat(notificationsOf(admin, "BATCH_FAILED", "g7b-it-no-tx")).isEmpty();
    }

    @Test
    @DisplayName("@Transactional の本体が commit したら、SYSTEM_ADMIN 全員に BATCH_COMPLETED の通知行が1件ずつ作られる")
    void commit後に成功通知() {
        Long admin = systemAdmin("tx-ok");
        Long admin2 = systemAdmin("tx-ok2");

        probe.txOk();

        awaitNotification(admin, "BATCH_COMPLETED", "g7b-it-tx-ok");
        awaitNotification(admin2, "BATCH_COMPLETED", "g7b-it-tx-ok");
        drainEventPool();
        assertThat(notificationsOf(admin, "BATCH_COMPLETED", "g7b-it-tx-ok")).as("ちょうど1件").hasSize(1);
        assertThat(notificationsOf(admin2, "BATCH_COMPLETED", "g7b-it-tx-ok")).as("2人目もちょうど1件").hasSize(1);
        assertThat(notificationsOf(admin, "BATCH_FAILED", "g7b-it-tx-ok")).isEmpty();
    }

    @Test
    @DisplayName("@Transactional の本体が例外で rollback したら、失敗通知だけが作られ成功通知は0件")
    void rollbackで失敗通知のみ() {
        Long admin = systemAdmin("tx-rb");

        assertThatThrownBy(() -> probe.txThrows()).isInstanceOf(IllegalStateException.class)
                .hasMessage("本体の失敗（検証用）");

        awaitNotification(admin, "BATCH_FAILED", "g7b-it-tx-throws");
        drainEventPool();
        assertThat(notificationsOf(admin, "BATCH_COMPLETED", "g7b-it-tx-throws"))
                .as("rollback したので成功通知は無い").isEmpty();
    }

    @Test
    @DisplayName("本体が成功しても commit に失敗したら、失敗通知だけが作られ成功通知は0件（注入点到達も確認）")
    void commit失敗で失敗通知のみ() {
        Long admin = systemAdmin("cm-fail");
        Long admin2 = systemAdmin("cm-fail2");
        saboteur.arm("CommitProbeEvent");

        assertThatThrownBy(() -> probe.commitFails()).isInstanceOf(IllegalStateException.class);

        assertThat(saboteur.tripped()).as("BEFORE_COMMIT 注入点への到達").isPositive();
        assertThat(BatchProbe.BODY_CALLS.get()).as("本体は実行された").isEqualTo(1);
        awaitNotification(admin, "BATCH_FAILED", "g7b-it-commit-fails");
        awaitNotification(admin2, "BATCH_FAILED", "g7b-it-commit-fails");
        drainEventPool();
        assertThat(notificationsOf(admin, "BATCH_COMPLETED", "g7b-it-commit-fails"))
                .as("commit に失敗したので成功通知は無い").isEmpty();
        assertThat(notificationsOf(admin2, "BATCH_COMPLETED", "g7b-it-commit-fails")).isEmpty();
        assertThat(latestLog("g7b-it-commit-fails").getStatus()).isEqualTo(BatchJobStatus.FAILED);
    }

    @Test
    @DisplayName("TXなしの本体が例外を投げたら、失敗通知が作られ元の例外がそのまま伝播する")
    void TXなし失敗で失敗通知() {
        Long admin = systemAdmin("nt-fail");

        assertThatThrownBy(() -> probe.bodyThrowsNoTx()).isInstanceOf(IllegalStateException.class)
                .hasMessage("本体の失敗・TXなし（検証用）");

        awaitNotification(admin, "BATCH_FAILED", "g7b-it-body-throws-no-tx");
    }

    // ═════════════════════════════════════════════════════════════════════
    // ログ書き込みの失敗が通知・本体に波及しない
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("開始ログの書き込みに失敗したら本体は動かず例外が伝播し、通知は0件")
    void 開始ログ失敗は本体を動かさず通知もしない() {
        Long admin = systemAdmin("st-fail");
        willThrow(new DataIntegrityViolationException("開始ログ失敗（検証用）"))
                .given(batchJobLogService).startJob("g7b-it-log-fails");

        assertThatThrownBy(() -> probe.logFails()).isInstanceOf(DataIntegrityViolationException.class);

        drainEventPool();
        assertThat(BatchProbe.BODY_CALLS.get()).as("本体は動かない").isZero();
        assertThat(notificationsOf(admin, "BATCH_COMPLETED", "g7b-it-log-fails")).isEmpty();
        assertThat(notificationsOf(admin, "BATCH_FAILED", "g7b-it-log-fails")).isEmpty();
    }

    @Test
    @DisplayName("完了ログの書き込みに失敗しても、成功通知は1回だけ作られる")
    void 完了ログ失敗でも成功通知() {
        Long admin = systemAdmin("cl-fail");
        willThrow(new DataIntegrityViolationException("完了ログ失敗（検証用）"))
                .given(batchJobLogService).completeJob(any(), eq(1));

        int result = probe.logFails();

        assertThat(result).isEqualTo(1);
        awaitNotification(admin, "BATCH_COMPLETED", "g7b-it-log-fails");
        drainEventPool();
        assertThat(notificationsOf(admin, "BATCH_COMPLETED", "g7b-it-log-fails")).hasSize(1);
    }

    @Test
    @DisplayName("失敗ログの書き込みに失敗しても、失敗通知は作られ元の例外が伝播する")
    void 失敗ログ失敗でも失敗通知と元の例外() {
        Long admin = systemAdmin("fl-fail");
        willThrow(new DataIntegrityViolationException("失敗ログ失敗（検証用）"))
                .given(batchJobLogService).failJob(any(), any());

        assertThatThrownBy(() -> probe.bodyThrowsNoTx()).isInstanceOf(IllegalStateException.class)
                .hasMessage("本体の失敗・TXなし（検証用）");

        awaitNotification(admin, "BATCH_FAILED", "g7b-it-body-throws-no-tx");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 被害半径の分離
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("管理者1人への配送が失敗しても、他の管理者には通知が届く（失敗対象への到達も確認）")
    void 管理者1人の失敗が他に波及しない() {
        Long bad = systemAdmin("pa-bad");
        Long good = systemAdmin("pa-good");
        willThrow(new DataIntegrityViolationException("配送失敗（検証用）"))
                .given(notificationService).createNotification(
                        eq(bad), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        probe.perAdmin();

        awaitNotification(good, "BATCH_COMPLETED", "g7b-it-per-admin");
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(createNotificationCallsFor(bad)).as("失敗対象への配送到達").isPositive());
        drainEventPool();
        assertThat(notificationsOf(bad, "BATCH_COMPLETED", "g7b-it-per-admin")).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 停止（BackgroundFeaturePolicy）との順序契約（BackgroundFeatureSkipRecorder の既存契約を保つ）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("停止への初回遷移は SKIPPED を1件だけ記録し、本体・通知・通常ログは0件。連続停止2回目以降は積み増さない")
    void 停止中は初回遷移だけ記録し通知しない() {
        Long admin = systemAdmin("gate");

        probe.gated();
        probe.gated();
        probe.gated();

        drainEventPool();
        List<BatchJobLogEntity> logs = transactionTemplate.execute(tx ->
                batchJobLogRepository.findByJobNameOrderByStartedAtDesc("g7b-it-gated"));
        assertThat(BatchProbe.BODY_CALLS.get()).as("停止中は本体を呼ばない").isZero();
        assertThat(logs).as("SKIPPED が初回遷移で1件だけ").hasSize(1);
        assertThat(logs.get(0).getStatus()).isEqualTo(BatchJobStatus.SKIPPED);
        assertThat(notificationsOf(admin, "BATCH_COMPLETED", "g7b-it-gated")).isEmpty();
        assertThat(notificationsOf(admin, "BATCH_FAILED", "g7b-it-gated")).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void awaitNotification(Long userId, String type, String jobName) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationsOf(userId, type, jobName)).isNotEmpty());
    }

    private long createNotificationCallsFor(Long userId) {
        return Mockito.mockingDetails(notificationService).getInvocations().stream()
                .filter(i -> "createNotification".equals(i.getMethod().getName()))
                .filter(i -> userId.equals(i.getArgument(0)))
                .count();
    }

    private BatchJobLogEntity latestLog(String jobName) {
        return transactionTemplate.execute(tx ->
                batchJobLogRepository.findFirstByJobNameOrderByStartedAtDescIdDesc(jobName).orElseThrow());
    }

    /**
     * event-pool が空（実行中0・待ち行列0）の状態が連続5回（100ms 間隔）続くまで待つ。
     * 一瞬の空きで抜けないよう連続観測を条件にする。「通知が来ないこと」を見る前に必ず通す。
     */
    private void drainEventPool() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        AtomicInteger idleStreak = new AtomicInteger();
        await().atMost(30, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).until(() -> {
            boolean idle = pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty();
            if (!idle) {
                idleStreak.set(0);
                return false;
            }
            return idleStreak.incrementAndGet() >= 5;
        });
    }

    private List<NotificationEntity> notificationsOf(Long userId, String type, String jobName) {
        List<NotificationEntity> all = transactionTemplate.execute(tx ->
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 100)).getContent());
        return all.stream()
                .filter(n -> type.equals(n.getNotificationType()))
                .filter(n -> n.getTitle() != null && n.getTitle().contains(jobName))
                .toList();
    }

    private Long systemAdmin(String tag) {
        String nonce = tag + "-" + System.nanoTime();
        return transactionTemplate.execute(tx -> {
            Long userId = insertUser("g7b-" + nonce + "@example.com");
            MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null);
            return userId;
        });
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'G7B', 'テスト', 'G7B テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
