package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.outbox.NotificationOutboxIngestService;
import com.mannschaft.app.team.service.TeamNotificationOutboxSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 通知 outbox P1 — 起こし（AFTER_COMMIT の即時 drain）の試練 IT。
 *
 * <ul>
 *   <li>OB10: ポーラーが動いていなくても、コミット後の起こしで5秒以内にジョブができる</li>
 *   <li>OB15: 起こしの executor（{@code notification-outbox-pool}）を飽和させて CallerRuns を強制しても、
 *       取り込みは独立した tx でコミットされ（別の接続からジョブが見える）、その後で RELAYED になる</li>
 * </ul>
 *
 * <p>このクラスだけ起こしを有効にする（試験プロファイルの既定は無効）。予備ポーラーは試験プロファイルでは
 * スケジュールされない（{@code ShedLockConfig} の {@code @EnableScheduling} が test で無効）。</p>
 */
@TestPropertySource(properties = "mannschaft.notification.outbox.nudge-enabled=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("通知 outbox P1 起こし（AFTER_COMMIT の即時 drain・CallerRuns）")
class NotificationOutboxNudgeIT extends TeamNotificationOutboxItSupport {

    @Autowired
    @Qualifier("notification-outbox-pool")
    private Executor outboxPool;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private NotificationOutboxIngestService ingestSpy;

    @MockitoSpyBean
    private TeamNotificationOutboxSource sourceSpy;

    @Test
    @DisplayName("OB10 ポーラーが止まっていても、コミット後の起こしで5秒以内にジョブができ、outbox は RELAYED になる")
    void ob10_コミット後の起こしで5秒以内にジョブができる() {
        OrgFx org = inTx(this::newOrg);
        long membershipId = appendNotice(org);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(jobCountOf(membershipId)).isEqualTo(1);
                    assertThat(statusOf(membershipId)).isEqualTo("RELAYED");
                });
    }

    @Test
    @DisplayName("OB15 executor を飽和させ CallerRuns で AFTER_COMMIT 内に同期実行されても、取り込みは独立した tx でコミットされ、別の接続からジョブが見えた後に RELAYED になる")
    void ob15_CallerRunsでも取り込みは独立したtxでコミットされる() throws Exception {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) outboxPool;
        assertThat(pool.getQueueCapacity()).as("飽和させる前提（キューが大きすぎない）").isLessThanOrEqualTo(1000);
        OrgFx org = inTx(this::newOrg);
        long membershipId = appendNotice(org); // 先に1件流して、飽和前の起こしが済むのを待つ
        await().atMost(Duration.ofSeconds(5)).until(() -> "RELAYED".equals(statusOf(membershipId)));

        Thread testThread = Thread.currentThread();
        List<Boolean> ingestOnCallerThread = new CopyOnWriteArrayList<>();
        List<Long> jobsSeenFromOtherConnectionAtMark = new CopyOnWriteArrayList<>();
        long target = nextFakeMembershipId();
        UUID key = idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, target);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            ingestOnCallerThread.add(Thread.currentThread() == testThread);
            return result;
        }).when(ingestSpy).ingest(any());
        doAnswer(invocation -> {
            jobsSeenFromOtherConnectionAtMark.add(countJobsOnFreshConnection(key));
            return invocation.callRealMethod();
        }).when(sourceSpy).markRelayed(any(), any(), any());

        CountDownLatch release = new CountDownLatch(1);
        int blockers = pool.getMaxPoolSize() + pool.getQueueCapacity();
        try {
            for (int i = 0; i < blockers; i++) {
                pool.execute(() -> {
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // コミット → AFTER_COMMIT → @Async が拒否され CallerRuns → この（テストの）スレッドで同期に drain
            appendNotice(org, target);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

            assertThat(ingestOnCallerThread).as("取り込みは CallerRuns でコミットしたスレッド上で走った")
                    .isNotEmpty().allMatch(Boolean::booleanValue);
            assertThat(jobsSeenFromOtherConnectionAtMark).as("印付けの時点で、ジョブは別の接続から見える（独立 tx でコミット済み）")
                    .isNotEmpty().allMatch(count -> count == 1L);
            assertThat(countJobsOnFreshConnection(key)).isEqualTo(1L);
            assertThat(outboxRow(key).get("status")).isEqualTo("RELAYED");
        } finally {
            release.countDown();
        }
    }

    /** 業務の tx の接続と無関係な、新しい接続でジョブを数える。 */
    private long countJobsOnFreshConnection(UUID key) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM notification_fanout_jobs WHERE source_event_uuid = ?")) {
            statement.setBytes(1, uuidBytes(key));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
