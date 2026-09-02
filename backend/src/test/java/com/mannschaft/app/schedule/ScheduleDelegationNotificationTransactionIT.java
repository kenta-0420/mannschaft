package com.mannschaft.app.schedule;

import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleDelegationRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.schedule.service.ScheduleDelegationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Issue #2990 L2 — スケジュール代理出席の通知トランザクション境界の実 DB 検証。
 *
 * <h2>是正前に何が巻き戻っていたか</h2>
 * <p>{@code ScheduleDelegationNotifier} は素の {@code @Component} で、
 * {@code ScheduleDelegationService} の {@code @Transactional} メソッドから同期的に呼ばれていた。
 * 内部の {@code createNotification} は既定の {@code REQUIRED} 伝播で業務トランザクションに参加するため、
 * 通知の DB 例外が rollback-only を残して業務処理ごと巻き戻していた。台帳キーは 2 件:</p>
 * <ul>
 *   <li>{@code ProxyDelegationCleanupBatchService#cleanupScheduleDelegations
 *       -> TX_NOTIFY_VIA_DELEGATE | ROLLBACK_COUPLED}（委譲先 {@code cancelOnMemberLeft}）—
 *       1 件の通知失敗で、その回に処理した孤立委任の CANCELLED 化が全件巻き戻る。</li>
 *   <li>{@code ScheduleAttendanceService#respondAttendance
 *       -> TX_NOTIFY_VIA_DELEGATE | ROLLBACK_COUPLED}（委譲先 {@code onDelegatorAttendanceChanged}）—
 *       通知失敗で利用者本人の出欠回答そのものが失われる。</li>
 * </ul>
 *
 * <h2>この IT が欠陥を捕まえる仕組み</h2>
 * <p>{@link NotificationService#createNotification} を spy して例外を投げさせる。ここは
 * 是正前・是正後のどちらの経路でも必ず通るため、是正前のコードでは委任の CANCELLED 化が
 * {@code UnexpectedRollbackException} で巻き戻って本テストは赤になる。</p>
 *
 * <p><b>実測済み</b>（2026-09-02）: 是正前のコード（{@code 362cf1bca9}）へこの IT を当てて
 * <b>2 件とも赤になること</b>を確認した（tests=2 / failures=2 / skipped=0）。失敗の中身も
 * {@code TransactionAspectSupport} 経由で {@code ScheduleDelegationService} の
 * {@code @Transactional} 境界から巻き戻りが抜けてくる形であり、狙いどおりの理由で赤い。
 * 本戦役の 3 本の IT のうち、<b>巻き戻りそのものを再現できているのはこの IT だけである</b>
 * （他 2 本の限界はそれぞれのクラス javadoc に明記した）。</p>
 *
 * <p>検証対象は<b>委譲先の入口メソッド</b>（{@code cancelOnMemberLeft} /
 * {@code onDelegatorAttendanceChanged}）そのものである。台帳が
 * {@code ROLLBACK_COUPLED} と判定した根拠がまさにこの 2 メソッドの
 * {@code @Transactional(REQUIRED)} 宣言であり、巻き戻りの境界はここに立っているためである。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むとコミットが起きず
 * リスナーが発火しないまま緑になる（偽の緑）。フィクスチャ投入・検証読み取りは
 * {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L2 スケジュール代理出席の通知トランザクション境界（実DB）")
class ScheduleDelegationNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ScheduleDelegationService scheduleDelegationService;

    @Autowired
    private ScheduleDelegationRepository delegationRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 外部 API 呼び出しは本テストの対象外のため遮断する。 */
    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    /** 是正前・是正後の双方で必ず通る一点。ここを失敗させて巻き戻りの有無を測る。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    @Test
    @DisplayName("退会連動の通知が失敗しても、孤立委任の CANCELLED 化はコミットされる（日次バッチ経路）")
    void 通知失敗でも退会連動の代理取消はコミットされる() {
        long teamId = 940_000_000L + (System.nanoTime() % 1_000_000L);
        long delegatorId = teamId + 1L;
        long delegateId = teamId + 2L;
        Long scheduleId = insertSchedule(teamId, delegatorId);
        UUID delegationId = insertDelegation(
                scheduleId, teamId, delegatorId, delegateId, ScheduleDelegationStatus.ACCEPTED);

        failNotification();

        ScheduleDelegationEntity target = transactionTemplate.execute(
                tx -> delegationRepository.findById(delegationId).orElseThrow());
        scheduleDelegationService.cancelOnMemberLeft(target, delegateId);

        assertThat(reloadStatus(delegationId))
                .as("通知が失敗しても孤立委任の取消は巻き戻らない")
                .isEqualTo(ScheduleDelegationStatus.CANCELLED);
        awaitDeliveryAttempted();
    }

    @Test
    @DisplayName("自動取消の通知が失敗しても、PENDING 代理の CANCELLED 化はコミットされる（出欠回答経路）")
    void 通知失敗でも出欠連動の代理自動取消はコミットされる() {
        long teamId = 950_000_000L + (System.nanoTime() % 1_000_000L);
        long delegatorId = teamId + 1L;
        long delegateId = teamId + 2L;
        Long scheduleId = insertSchedule(teamId, delegatorId);
        UUID delegationId = insertDelegation(
                scheduleId, teamId, delegatorId, delegateId, ScheduleDelegationStatus.PENDING);

        failNotification();

        scheduleDelegationService.onDelegatorAttendanceChanged(
                scheduleId, delegatorId, AttendanceStatus.ATTENDING);

        assertThat(reloadStatus(delegationId))
                .as("通知が失敗しても出欠回答に伴う代理の自動取消は巻き戻らない")
                .isEqualTo(ScheduleDelegationStatus.CANCELLED);
        awaitDeliveryAttempted();
    }

    // ---- フィクスチャ / ヘルパ ----

    private Long insertSchedule(long teamId, long createdBy) {
        LocalDateTime start = LocalDateTime.now().plusDays(3).withNano(0);
        return transactionTemplate.execute(tx -> scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("#2990 L2 代理出席 通知境界検証")
                .startAt(start)
                .endAt(start.plusHours(2))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(createdBy)
                .build()).getId());
    }

    private UUID insertDelegation(Long scheduleId, long teamId, long delegatorId, long delegateId,
                                  ScheduleDelegationStatus status) {
        return transactionTemplate.execute(tx -> delegationRepository.save(
                ScheduleDelegationEntity.builder()
                        .scheduleId(scheduleId)
                        .delegatorId(delegatorId)
                        .delegateId(delegateId)
                        .teamId(teamId)
                        .status(status)
                        .reason("#2990 L2 検証")
                        .build()).getId());
    }

    private void failNotification() {
        willThrow(new RuntimeException("模擬通知失敗（#2990 L2 検証用）"))
                .given(notificationService).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private ScheduleDelegationStatus reloadStatus(UUID delegationId) {
        return transactionTemplate.execute(
                tx -> delegationRepository.findById(delegationId).orElseThrow().getStatus());
    }

    /** AFTER_COMMIT + @Async のリスナーが実際に配送を試みたことの裏取り（非同期のため待つ）。 */
    private void awaitDeliveryAttempted() {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()));
    }
}
