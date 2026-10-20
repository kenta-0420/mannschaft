package com.mannschaft.app.reservation;

import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;
import com.mannschaft.app.reservation.dto.EmergencyClosureResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationWaitlistEntryEntity;
import com.mannschaft.app.reservation.event.EmergencyClosureNotificationEvent;
import com.mannschaft.app.reservation.event.ReservationReminderDispatchEvent;
import com.mannschaft.app.reservation.event.ReservationSlotReopenedEvent;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationWaitlistEntryRepository;
import com.mannschaft.app.reservation.service.EmergencyClosureService;
import com.mannschaft.app.reservation.service.ReservationReminderDispatchBatchService;
import com.mannschaft.app.reservation.service.ReservationReminderDispatchRunner;
import com.mannschaft.app.reservation.service.ReservationWaitlistNotifyRunner;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #2997（CMP-260827-1152）G3 — 予約ドメインの付随通知を業務トランザクションから切り離したことを実 DB で検証する。
 *
 * <p>対象: {@code EmergencyClosureService#sendClosure}（臨時休業）、
 * {@code ReservationReminderDispatchBatchService#dispatchDueReminders}（予約リマインド実送信）、
 * {@code ReservationSlotReopenedEvent} 経由のキャンセル待ち空き通知（旧 {@code notifySlotReopened}）。</p>
 *
 * <ul>
 *   <li>AC-A2: コミットされたら受信者全員に通知行ができる。</li>
 *   <li>AC-A: 業務TXがロールバックしたら通知行は 1 件も作られない（非同期完了は drain で待つ。sleep のみは使わない）。</li>
 *   <li>AC-B / AC-B2: 通知配送が失敗しても業務行（休業・キャンセル・確認行）は残り、1 人の失敗が他に波及しない。
 *       障害は {@link NotificationDeliveryRunner#sendOne} の spy で注入し、<b>呼出回数を await</b> して
 *       注入点に到達した証拠を取る（到達しなければ失敗＝旧実装では red になる）。</li>
 *   <li>リマインド: publish〜claim 前 PENDING／claim 後 SENT／全員例外で PENDING に戻り次周回再送／
 *       成功・deny は SENT／event-pool 投入拒否は PENDING のまま次周回再送。</li>
 *   <li>キャンセル待ち: 行ごとの条件付き claim（notified_at）、例外なら prev に戻す、古い戻しが新しい claim を消さない、
 *       deny は印保持、抑止期限内は再通知しない。</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} は付けない（付けるとコミットが起きず AFTER_COMMIT が発火しない偽の緑になる）。
 * フィクスチャは {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@RecordApplicationEvents
@Import(ReservationNotificationTransactionIT.CommitFailer.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G3 予約ドメイン付随通知のトランザクション分離の実DB検証")
class ReservationNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    /** テストごとに重なりにくい ID を払い出す（別テストの残骸と衝突しない）。 */
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 100_000_000L * 100L);

    private static final LocalDate FUTURE = LocalDate.now().plusMonths(2);

    @Autowired private EmergencyClosureService closureService;
    @Autowired private ReservationReminderDispatchBatchService reminderBatch;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private ReservationSlotRepository slotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationReminderRepository reminderRepository;
    @Autowired private ReservationWaitlistEntryRepository waitlistRepository;
    @Autowired private EmergencyClosureRepository closureRepository;
    @Autowired private EmergencyClosureConfirmationRepository confirmationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired private Clock clock;
    @Autowired @Qualifier("event-pool") private Executor eventPool;
    @PersistenceContext private EntityManager em;

    @Autowired private ApplicationEvents applicationEvents;

    @MockitoSpyBean private NotificationDeliveryRunner notificationDeliveryRunner;
    @MockitoSpyBean private ReservationReminderDispatchRunner reminderRunner;
    @MockitoSpyBean private ReservationWaitlistNotifyRunner waitlistNotifyRunner;

    @BeforeEach
    void cleanReminders() {
        // バッチは期限到来済みの PENDING を全件拾う。別テストの残骸を拾わないよう空にする。
        reminderRepository.deleteAll();
    }

    @AfterEach
    void drainEventPool() {
        // 非同期配送の残りが次のテストへ漏れないよう、event-pool が静まるのを待つ。
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        await().atMost(20, TimeUnit.SECONDS).until(() ->
                pool.getActiveCount() == 0 && pool.getThreadPoolExecutor().getQueue().isEmpty());
    }

    // ═════════════════════════════════════════════
    // 臨時休業（sendClosure）
    // ═════════════════════════════════════════════

    @Test
    @DisplayName("AC-A2（休業）: コミットされたら対象の予約者全員に通知行が作られる（同一ユーザーの複数予約は1件）")
    void closureCommitCreatesNotificationForEveryone() {
        ClosureFx fx = closureFixture(3);

        EmergencyClosureResponse response = sendClosure(fx, true);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long user : fx.userIds) {
                assertThat(rows(user, "EMERGENCY_CLOSURE", "EMERGENCY_CLOSURE", response.getId()))
                        .as("対象の予約者全員に EMERGENCY_CLOSURE の通知行").hasSize(1);
            }
        });
        assertThat(response.getSentCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-A（休業）: 業務TXがロールバックしたら、通知行は 1 件も作られない（休業行も残らない）")
    void closureRollbackCreatesNoNotification() {
        ClosureFx fx = closureFixture(2);
        AtomicLong closureId = new AtomicLong();

        // publish の後・commit の前（BEFORE_COMMIT）に失敗させる。業務本体からの例外ではなく、配送要求が publish 済みでも
        // コミットが失敗すれば AFTER_COMMIT の配送が起きないことを見る。
        CommitFailer.armClosure();
        try {
            assertThatThrownBy(() -> closureId.set(
                    closureService.sendClosure(fx.teamId, fx.operatorId, closureRequest(fx, true)).getId()))
                    .hasStackTraceContaining("模擬コミット失敗");
        } finally {
            CommitFailer.disarm();
        }
        // publish 済みイベントの実 ID（コミット失敗で行は残らないが、配送要求は publish されている）。
        List<EmergencyClosureNotificationEvent> published = applicationEvents
                .stream(EmergencyClosureNotificationEvent.class).toList();
        assertThat(published).as("配送要求は publish 済み（commit 前に失敗させた証拠）").hasSize(1);
        Long publishedClosureId = published.get(0).closureId();
        closureId.set(publishedClosureId);
        assertThat(closureRepository.findById(publishedClosureId)).as("休業行はコミット失敗で残らない").isEmpty();
        // 非同期配送が走り切る余地を与えたうえで 0 件であること（drain 後に確認する）。
        drainEventPool();
        for (Long user : fx.userIds) {
            assertThat(rows(user, "EMERGENCY_CLOSURE", "EMERGENCY_CLOSURE", closureId.get())).isEmpty();
        }
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> r != null
                && "EMERGENCY_CLOSURE".equals(r.sourceType()) && publishedClosureId.equals(r.sourceId())));
    }

    @Test
    @DisplayName("AC-B（休業）: 通知配送が全員失敗しても、休業・キャンセル・確認行はコミットされ、枠は空きに戻る")
    void closureDeliveryFailureStillCommitsBusinessRows() {
        ClosureFx fx = closureFixture(2);
        willThrow(new RuntimeException("模擬通知配送失敗（AC-B 検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        EmergencyClosureResponse response = sendClosure(fx, true);

        // 注入点（sendOne）に到達した証拠。到達しなければ「通知が業務TX内で処理されている」ことを意味する。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(2)).sendOne(argThat(r -> r != null
                        && "EMERGENCY_CLOSURE".equals(r.sourceType()) && response.getId().equals(r.sourceId()))));
        assertThat(closureRepository.findById(response.getId())).as("休業行が残る").isPresent();
        for (Long reservationId : fx.reservationIds) {
            assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                    .as("対象予約はキャンセルされている").isEqualTo(ReservationStatus.CANCELLED);
        }
        assertThat(confirmationRepository.findByEmergencyClosureIdAndUserId(response.getId(), fx.userIds.get(0)))
                .as("確認追跡行が残る").isPresent();
        assertThat(confirmationRepository.findByEmergencyClosureIdAndUserId(response.getId(), fx.userIds.get(1)))
                .isPresent();
        for (Long user : fx.userIds) {
            assertThat(rows(user, "EMERGENCY_CLOSURE", "EMERGENCY_CLOSURE", response.getId())).isEmpty();
        }
    }

    @Test
    @DisplayName("AC-B2（休業）: 受信者の 1 人だけ失敗しても、他の受信者の通知行は作られ、業務行は残る")
    void closureOneRecipientFailureDoesNotAffectOthers() {
        ClosureFx fx = closureFixture(3);
        Long failedUser = fx.userIds.get(1);
        failFor(failedUser);

        EmergencyClosureResponse response = sendClosure(fx, false);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(notificationDeliveryRunner, times(3)).sendOne(argThat(r -> r != null
                    && "EMERGENCY_CLOSURE".equals(r.sourceType()) && response.getId().equals(r.sourceId())));
            assertThat(rows(fx.userIds.get(0), "EMERGENCY_CLOSURE", "EMERGENCY_CLOSURE", response.getId())).hasSize(1);
            assertThat(rows(fx.userIds.get(2), "EMERGENCY_CLOSURE", "EMERGENCY_CLOSURE", response.getId())).hasSize(1);
        });
        assertThat(rows(failedUser, "EMERGENCY_CLOSURE", "EMERGENCY_CLOSURE", response.getId())).isEmpty();
        assertThat(closureRepository.findById(response.getId())).isPresent();
    }

    // ═════════════════════════════════════════════
    // 予約リマインド実送信（dispatchDueReminders）
    // ═════════════════════════════════════════════

    @Test
    @DisplayName("AC-A2（リマインド）: コミットされたら予約者に通知行が作られ、配送後に SENT になる")
    void reminderCommitCreatesNotificationAndMarksSent() {
        ReminderFx fx = reminderFixture(2);

        reminderBatch.dispatchDueReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (int i = 0; i < fx.userIds.size(); i++) {
                assertThat(rows(fx.userIds.get(i), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(i)))
                        .hasSize(1);
                assertThat(reminderStatus(fx.reminderIds.get(i))).isEqualTo(ReminderStatus.SENT);
            }
        });
    }

    @Test
    @DisplayName("claim 後は SENT: 配送（sendOne）が走る時点で既に印は SENT になっている")
    void reminderIsSentWhenDeliveryStarts() {
        ReminderFx fx = reminderFixture(1);
        List<ReminderStatus> statusAtDelivery = new ArrayList<>();
        willAnswer(inv -> {
            statusAtDelivery.add(reminderStatus(fx.reminderIds.get(0)));
            return inv.callRealMethod();
        }).given(notificationDeliveryRunner).sendOne(any());

        reminderBatch.dispatchDueReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(statusAtDelivery).as("配送は 1 回だけ、claim 後（SENT）に走る").containsExactly(ReminderStatus.SENT));
    }

    @Test
    @DisplayName("publish〜claim 前は PENDING: claim の直前で配送を止めている間は印を立てない（claim 後に SENT）")
    void reminderStaysPendingUntilClaim() {
        ReminderFx fx = reminderFixture(1);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch reached = new CountDownLatch(1);
        willAnswer(inv -> {
            reached.countDown();
            awaitQuietly(gate);
            return inv.callRealMethod();
        }).given(reminderRunner).claim(any(), any());
        try {
            reminderBatch.dispatchDueReminders();

            // リスナーが claim に到達した（注入点到達の証拠）が、まだ通過していない間は PENDING・通知なし。
            await().atMost(10, TimeUnit.SECONDS).until(() -> reached.getCount() == 0);
            assertThat(reminderStatus(fx.reminderIds.get(0))).as("claim 前は PENDING").isEqualTo(ReminderStatus.PENDING);
            assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0)))
                    .isEmpty();
        } finally {
            gate.countDown();
        }
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(reminderStatus(fx.reminderIds.get(0))).isEqualTo(ReminderStatus.SENT);
            assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0)))
                    .hasSize(1);
        });
    }

    @Test
    @DisplayName("AC-B（リマインド）: 配送が例外で失敗したら PENDING に戻り、バッチは正常終了し、次周回で再送される")
    void reminderDeliveryFailureReturnsToPendingAndIsRetried() {
        ReminderFx fx = reminderFixture(1);
        willThrow(new RuntimeException("模擬通知配送失敗")).given(notificationDeliveryRunner).sendOne(any());

        reminderBatch.dispatchDueReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(1)).sendOne(argThat(r -> r != null
                        && fx.reservationIds.get(0).equals(r.sourceId()))));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(reminderStatus(fx.reminderIds.get(0))).as("全員失敗なので PENDING に戻る")
                        .isEqualTo(ReminderStatus.PENDING));
        assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0))).isEmpty();

        // 障害が解消したものとして実処理に戻し、次周回で再送される。
        willAnswer(inv -> inv.callRealMethod()).given(notificationDeliveryRunner).sendOne(any());
        reminderBatch.dispatchDueReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0)))
                    .hasSize(1);
            assertThat(reminderStatus(fx.reminderIds.get(0))).isEqualTo(ReminderStatus.SENT);
        });
    }

    @Test
    @DisplayName("AC-B2（リマインド）: 1 件の配送が失敗しても、他のリマインドは届き SENT になる")
    void reminderOneFailureDoesNotAffectOthers() {
        ReminderFx fx = reminderFixture(3);
        failFor(fx.userIds.get(1));

        reminderBatch.dispatchDueReminders();

        // 失敗を注入した対象（userIds[1]）への sendOne に実際に到達した証拠（呼出回数）。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(1)).sendOne(argThat(r -> r != null
                        && fx.userIds.get(1).equals(r.recipientUserId())
                        && fx.reservationIds.get(1).equals(r.sourceId()))));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0))).hasSize(1);
            assertThat(rows(fx.userIds.get(2), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(2))).hasSize(1);
            assertThat(reminderStatus(fx.reminderIds.get(0))).isEqualTo(ReminderStatus.SENT);
            assertThat(reminderStatus(fx.reminderIds.get(2))).isEqualTo(ReminderStatus.SENT);
            assertThat(reminderStatus(fx.reminderIds.get(1))).as("失敗した 1 件は PENDING").isEqualTo(ReminderStatus.PENDING);
        });
    }

    @Test
    @DisplayName("deny でも SENT を保持する（再送しない・通知行は無い）")
    void reminderDenyKeepsSent() {
        ReminderFx fx = reminderFixture(1);
        willReturn(NotificationDeliveryResult.VISIBILITY_DENIED).given(notificationDeliveryRunner).sendOne(any());

        reminderBatch.dispatchDueReminders();

        // claim（SENT）だけでなく、配送（sendOne = deny）に到達するまで待ってから観測する。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(1)).sendOne(argThat(r -> r != null
                        && fx.reservationIds.get(0).equals(r.sourceId()))));
        drainEventPool();
        assertThat(reminderStatus(fx.reminderIds.get(0))).as("deny でも SENT を保持").isEqualTo(ReminderStatus.SENT);
        assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0))).isEmpty();
    }

    @Test
    @DisplayName("SENT 後に再実行しても再送しない（二重送信なし）")
    void reminderRerunAfterSentDoesNotResend() {
        ReminderFx fx = reminderFixture(1);
        reminderBatch.dispatchDueReminders();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(reminderStatus(fx.reminderIds.get(0))).isEqualTo(ReminderStatus.SENT));

        reminderBatch.dispatchDueReminders();

        await().during(2, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0)))
                        .hasSize(1));
        verify(notificationDeliveryRunner, times(1)).sendOne(argThat(r -> r != null
                && fx.reservationIds.get(0).equals(r.sourceId())));
    }

    @Test
    @DisplayName("event-pool 投入拒否: 印は立たず PENDING のまま、バッチは正常終了し、次周回で再送される")
    void reminderPoolRejectionLeavesPendingAndIsRetried() throws Exception {
        ReminderFx fx = reminderFixture(1);
        Long reminderId = fx.reminderIds.get(0);
        // 拒否ハンドラ（AsyncConfig の ERROR ログ）が呼ばれたことを到達の証拠として数える。
        ch.qos.logback.classic.Logger asyncLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.mannschaft.app.config.AsyncConfig.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> rejections =
                new ch.qos.logback.core.read.ListAppender<>();
        rejections.start();
        asyncLogger.addAppender(rejections);
        CountDownLatch release = new CountDownLatch(1);
        try {
            saturateEntirePool(release);
            // 前提: event-pool が実際に満杯であること（前提が崩れていれば、ここで原因が分かる形で落ちる）。
            assertPoolRejects("バッチ実行前");
            long rejectionsBefore = rejectionCount(rejections);

            reminderBatch.dispatchDueReminders();

            // 配送リスナーの投入が拒否された証拠（拒否ハンドラの呼出）を待つ。
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(rejectionCount(rejections)).as("リスナー投入が event-pool に拒否された").isGreaterThan(rejectionsBefore));
            assertThat(reminderStatus(reminderId)).as("投入拒否では印を立てない").isEqualTo(ReminderStatus.PENDING);
            assertPoolRejects("バッチ実行後");
        } finally {
            release.countDown();
            asyncLogger.detachAppender(rejections);
            rejections.stop();
        }
        drainEventPool();
        // 拒否された配送は後から実行されない（claim に一度も到達していない）。
        verify(reminderRunner, never()).claim(eq(reminderId), any());
        assertThat(reminderStatus(reminderId)).isEqualTo(ReminderStatus.PENDING);
        assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0))).isEmpty();

        reminderBatch.dispatchDueReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0))).hasSize(1);
            assertThat(reminderStatus(reminderId)).isEqualTo(ReminderStatus.SENT);
        });
    }

    /** event-pool が満杯で、いま投入すると拒否されること（満杯が成立している前提の確認。受理された場合は即座に流れて消える空タスク）。 */
    private void assertPoolRejects(String when) {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        assertThatThrownBy(() -> eventPool.execute(() -> { }))
                .as("event-pool が満杯であること（%s）: poolSize=%d active=%d queued=%d", when,
                        pool.getPoolSize(), pool.getActiveCount(), pool.getThreadPoolExecutor().getQueue().size())
                .isInstanceOf(org.springframework.core.task.TaskRejectedException.class);
    }

    private static long rejectionCount(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        return new ArrayList<>(appender.list).stream()
                .filter(e -> e.getFormattedMessage().contains("event-pool 投入拒否")).count();
    }

    @Test
    @DisplayName("境界: 予約または枠が解決できないリマインドは PENDING のままスキップされ、他は届く")
    void reminderUnresolvedReservationOrSlotIsSkippedAndPending() {
        ReminderFx fx = reminderFixture(1);
        // 予約が存在しないリマインド。
        Long orphanReminder = transactionTemplate.execute(tx -> reminderRepository.save(
                ReservationReminderEntity.builder().reservationId(next()).remindAt(dueAt()).build()).getId());
        // 枠が存在しない予約のリマインド。
        Long noSlotUser = next();
        Long noSlotReservation = transactionTemplate.execute(tx -> reservationRepository.save(
                ReservationEntity.builder().reservationSlotId(next()).lineId(1L).teamId(fx.teamId)
                        .userId(noSlotUser).status(ReservationStatus.CONFIRMED).build()).getId());
        Long noSlotReminder = transactionTemplate.execute(tx -> reminderRepository.save(
                ReservationReminderEntity.builder().reservationId(noSlotReservation).remindAt(dueAt()).build()).getId());

        reminderBatch.dispatchDueReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(reminderStatus(fx.reminderIds.get(0))).isEqualTo(ReminderStatus.SENT));
        drainEventPool();
        assertThat(reminderStatus(orphanReminder)).isEqualTo(ReminderStatus.PENDING);
        assertThat(reminderStatus(noSlotReminder)).isEqualTo(ReminderStatus.PENDING);
        assertThat(rows(noSlotUser, "RESERVATION_REMINDER", "RESERVATION", noSlotReservation)).isEmpty();
    }

    @Test
    @DisplayName("性能: reservation / slot は一括取得のまま（件数分の個別取得をしない）")
    void reminderKeepsBulkFetch() {
        ReminderFx fx = reminderFixture(5);
        long before = statementCount();

        reminderBatch.dispatchDueReminders();
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long reminderId : fx.reminderIds) {
                assertThat(reminderStatus(reminderId)).isEqualTo(ReminderStatus.SENT);
            }
        });
        drainEventPool();
        long perItem = (statementCount() - before) / 5;

        // 1 件あたりの SQL は定数個（reservation/slot の個別取得が混ざれば増える）。通知作成・配送・claim を含めた上限。
        assertThat(perItem).as("1 件あたりの SQL 本数").isLessThanOrEqualTo(40L);
    }

    // ═════════════════════════════════════════════
    // キャンセル待ち空き通知（ReservationSlotReopenedEvent）
    // ═════════════════════════════════════════════

    @Test
    @DisplayName("AC-A2（待ち）: 空き復帰がコミットされたら WAITING 全員に通知行が作られ、notified_at が立つ")
    void waitlistCommitCreatesNotificationsAndMarks() {
        WaitFx fx = waitFixture(3);

        publishReopened(fx);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long user : fx.userIds) {
                assertThat(rows(user, "RESERVATION_WAITLIST_OPENING", "RESERVATION", fx.slotId)).hasSize(1);
                assertThat(notifiedAt(fx.slotId, user)).as("通知した行は notified_at が立つ").isNotNull();
            }
        });
    }

    @Test
    @DisplayName("AC-A（待ち）: 空き復帰を含む業務TXがロールバックしたら、通知行は 1 件も作られず notified_at も立たない")
    void waitlistRollbackCreatesNoNotification() {
        WaitFx fx = waitFixture(2);

        CommitFailer.armSlot(fx.slotId);
        try {
            assertThatThrownBy(() -> publishReopened(fx)).hasStackTraceContaining("模擬コミット失敗");
        } finally {
            CommitFailer.disarm();
        }
        assertThat(applicationEvents.stream(ReservationSlotReopenedEvent.class)
                .filter(e -> fx.slotId.equals(e.getSlotId())).count())
                .as("空き復帰イベントは publish 済み（commit 前に失敗させた証拠）").isEqualTo(1L);

        drainEventPool();
        for (Long user : fx.userIds) {
            assertThat(rows(user, "RESERVATION_WAITLIST_OPENING", "RESERVATION", fx.slotId)).isEmpty();
            assertThat(notifiedAt(fx.slotId, user)).isNull();
        }
    }

    @Test
    @DisplayName("AC-B（待ち）: 全員の配送が失敗したら notified_at は prev（未通知なら null）に戻る")
    void waitlistAllFailureRestoresPrev() {
        WaitFx fx = waitFixture(2);
        willThrow(new RuntimeException("模擬通知配送失敗")).given(notificationDeliveryRunner).sendOne(any());

        publishReopened(fx);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(2)).sendOne(argThat(r -> r != null
                        && "RESERVATION_WAITLIST_OPENING".equals(r.notificationType())
                        && fx.slotId.equals(r.sourceId()))));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long user : fx.userIds) {
                assertThat(notifiedAt(fx.slotId, user)).as("失敗行は prev（null）に戻る").isNull();
            }
        });
        for (Long user : fx.userIds) {
            assertThat(rows(user, "RESERVATION_WAITLIST_OPENING", "RESERVATION", fx.slotId)).isEmpty();
        }
    }

    @Test
    @DisplayName("AC-B2（待ち）: 1 人の失敗が他の行に波及しない（他は通知行と notified_at が残り、失敗行だけ prev に戻る）")
    void waitlistOneFailureDoesNotAffectOthers() {
        WaitFx fx = waitFixture(3);
        Long failedUser = fx.userIds.get(1);
        failFor(failedUser);

        publishReopened(fx);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(notificationDeliveryRunner, times(3)).sendOne(argThat(r -> r != null
                    && "RESERVATION_WAITLIST_OPENING".equals(r.notificationType()) && fx.slotId.equals(r.sourceId())));
            assertThat(rows(fx.userIds.get(0), "RESERVATION_WAITLIST_OPENING", "RESERVATION", fx.slotId)).hasSize(1);
            assertThat(rows(fx.userIds.get(2), "RESERVATION_WAITLIST_OPENING", "RESERVATION", fx.slotId)).hasSize(1);
            assertThat(notifiedAt(fx.slotId, fx.userIds.get(0))).isNotNull();
            assertThat(notifiedAt(fx.slotId, fx.userIds.get(2))).isNotNull();
            assertThat(notifiedAt(fx.slotId, failedUser)).as("失敗行は prev に戻る").isNull();
        });
    }

    @Test
    @DisplayName("失敗行は『直前の notified_at（prev）』そのものに戻る（抑止期限を過ぎた古い通知時刻が保たれる）")
    void waitlistFailureRestoresExactPrev() {
        LocalDateTime prev = LocalDateTime.now(clock).minusHours(3).withNano(123_000_000);
        WaitFx fx = waitFixture(1, prev);
        willThrow(new RuntimeException("模擬通知配送失敗")).given(notificationDeliveryRunner).sendOne(any());

        publishReopened(fx);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(1)).sendOne(argThat(r -> r != null
                        && fx.slotId.equals(r.sourceId()))));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notifiedAt(fx.slotId, fx.userIds.get(0))).isEqualTo(prev));
    }

    @Test
    @DisplayName("古い戻しが新しい claim を消さない: 配送中に別の claim（新しい notified_at）が入っていたら、戻しはそれを上書きしない")
    void waitlistStaleReleaseDoesNotClobberNewerClaim() {
        WaitFx fx = waitFixture(1);
        Long user = fx.userIds.get(0);
        LocalDateTime newer = LocalDateTime.now(clock).plusMinutes(5).withNano(456_000_000);
        willAnswer(inv -> {
            // 配送の途中で、別の周回が同じ行を新しく claim した状況を再現してから失敗させる。
            // sendOne は REQUIRES_NEW のTXで走っており、この後に例外を投げるとそのTXはロールバックされる。
            // 別周回の claim は独立にコミットされるものなので、REQUIRES_NEW で書く（参加すると一緒に巻き戻って再現にならない）。
            requiresNewTemplate().executeWithoutResult(tx -> em.createQuery(
                            "UPDATE ReservationWaitlistEntryEntity w SET w.notifiedAt = :t WHERE w.slotId = :s AND w.userId = :u")
                    .setParameter("t", newer).setParameter("s", fx.slotId).setParameter("u", user).executeUpdate());
            throw new RuntimeException("模擬通知配送失敗");
        }).given(notificationDeliveryRunner).sendOne(any());

        publishReopened(fx);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(1)).sendOne(argThat(r -> r != null
                        && fx.slotId.equals(r.sourceId()))));
        drainEventPool();
        assertThat(notifiedAt(fx.slotId, user)).as("新しい claim の時刻が残る").isEqualTo(newer);
    }

    @Test
    @DisplayName("deny でも notified_at の印は保持する（戻さない・通知行は無い）")
    void waitlistDenyKeepsMark() {
        WaitFx fx = waitFixture(2);
        willReturn(NotificationDeliveryResult.VISIBILITY_DENIED).given(notificationDeliveryRunner).sendOne(any());

        publishReopened(fx);

        // claim だけでなく、全員ぶんの配送（sendOne = deny）に到達するまで待ってから観測する。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(2)).sendOne(argThat(r -> r != null
                        && fx.slotId.equals(r.sourceId()))));
        drainEventPool();
        for (Long user : fx.userIds) {
            assertThat(notifiedAt(fx.slotId, user)).as("deny は印を保持").isNotNull();
        }
        for (Long user : fx.userIds) {
            assertThat(rows(user, "RESERVATION_WAITLIST_OPENING", "RESERVATION", fx.slotId)).isEmpty();
        }
    }

    @Test
    @DisplayName("同じ空き復帰が 2 回起きても（抑止期限内）、1 行につき通知は 1 件だけ")
    void waitlistSameReopenTwiceNotifiesOnce() {
        WaitFx fx = waitFixture(2);

        publishReopened(fx);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long user : fx.userIds) {
                assertThat(rows(user, "RESERVATION_WAITLIST_OPENING", "RESERVATION", fx.slotId)).hasSize(1);
            }
        });
        publishReopened(fx);

        await().during(2, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long user : fx.userIds) {
                assertThat(rows(user, "RESERVATION_WAITLIST_OPENING", "RESERVATION", fx.slotId)).hasSize(1);
            }
        });
        verify(notificationDeliveryRunner, times(2)).sendOne(argThat(r -> r != null && fx.slotId.equals(r.sourceId())));
    }

    @Test
    @DisplayName("境界: 抑止期限（60分）の内側で通知済みの行は再通知されず、60分を過ぎた行は通知される")
    void waitlistSuppressionWindow() {
        WaitFx fresh = waitFixture(1, LocalDateTime.now(clock).minusMinutes(30));
        WaitFx stale = waitFixture(1, LocalDateTime.now(clock).minusMinutes(61));

        publishReopened(fresh);
        publishReopened(stale);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(stale.userIds.get(0), "RESERVATION_WAITLIST_OPENING", "RESERVATION", stale.slotId))
                        .hasSize(1));
        drainEventPool();
        assertThat(rows(fresh.userIds.get(0), "RESERVATION_WAITLIST_OPENING", "RESERVATION", fresh.slotId))
                .as("30分前に通知済みなので再通知しない").isEmpty();
    }

    @Test
    @DisplayName("境界: WAITING が 0 件なら何も送らない（枠は読んだ上でスキップ）")
    void waitlistNoWaitingDoesNothing() {
        WaitFx fx = waitFixture(0);
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                new ReservationSlotReopenedEvent(fx.teamId, fx.slotId)));

        drainEventPool();
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> r != null && fx.slotId.equals(r.sourceId())));
    }

    @Test
    @DisplayName("境界: 枠がまだ空いていない（FULL）なら通知しない")
    void waitlistSlotNotAvailableDoesNothing() {
        WaitFx fx = waitFixture(2, null, SlotStatus.FULL);

        publishReopened(fx);

        drainEventPool();
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> r != null && fx.slotId.equals(r.sourceId())));
        for (Long user : fx.userIds) {
            assertThat(notifiedAt(fx.slotId, user)).isNull();
        }
    }

    // ═════════════════════════════════════════════
    // 実装固有の契約（配送要求イベント・Runner の印）
    // ═════════════════════════════════════════════

    @Test
    @DisplayName("境界（休業）: 影響を受ける予約が 0 件なら配送要求を publish しない（休業履歴は残る）")
    void closureWithoutAffectedReservationsPublishesNothing() {
        Long teamId = next();
        ClosureFx empty = new ClosureFx(teamId, next(), FUTURE.plusDays(1), List.of(), List.of());

        EmergencyClosureResponse response = sendClosure(empty, false);

        assertThat(applicationEvents.stream(EmergencyClosureNotificationEvent.class).count())
                .as("0 人なら配送要求を発行しない").isZero();
        assertThat(closureRepository.findById(response.getId())).isPresent();
        assertThat(response.getSentCount()).isZero();
    }

    @Test
    @DisplayName("休業: 同じユーザーの複数予約は受信者を重複除去して 1 件だけ publish し、通知行も 1 件")
    void closureDeduplicatesRecipients() {
        ClosureFx fx = transactionTemplate.execute(tx -> {
            Long teamId = next();
            Long userId = next();
            LocalDate date = FUTURE.plusDays(next() % 300);
            List<Long> reservations = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ReservationSlotEntity slot = slotRepository.save(ReservationSlotEntity.builder()
                        .teamId(teamId).title("重複枠" + i).slotDate(date)
                        .startTime(LocalTime.of(8 + i, 0)).endTime(LocalTime.of(8 + i, 30))
                        .capacity(1).bookedCount(1).slotStatus(SlotStatus.FULL).build());
                reservations.add(reservationRepository.save(ReservationEntity.builder()
                        .reservationSlotId(slot.getId()).lineId(1L).teamId(teamId).userId(userId)
                        .status(ReservationStatus.CONFIRMED).build()).getId());
            }
            return new ClosureFx(teamId, next(), date, List.of(userId), reservations);
        });

        EmergencyClosureResponse response = sendClosure(fx, false);

        List<EmergencyClosureNotificationEvent> events = applicationEvents
                .stream(EmergencyClosureNotificationEvent.class).toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).recipientUserIds()).containsExactly(fx.userIds.get(0));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(fx.userIds.get(0), "EMERGENCY_CLOSURE", "EMERGENCY_CLOSURE", response.getId()))
                        .hasSize(1));
    }

    @Test
    @DisplayName("AC-A（リマインド）: 項目TXが publish 後・コミット前に失敗したら、通知行は作られず PENDING のまま")
    void reminderPublishRollbackCreatesNoNotification() {
        ReminderFx fx = reminderFixture(2);
        CommitFailer.armFor(fx.reminderIds.get(0));

        reminderBatch.dispatchDueReminders();

        // 失敗した 1 件目は PENDING のまま・通知なし。2 件目は届く（項目TXが独立）。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rows(fx.userIds.get(1), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(1)))
                    .hasSize(1);
            assertThat(reminderStatus(fx.reminderIds.get(1))).isEqualTo(ReminderStatus.SENT);
        });
        drainEventPool();
        assertThat(reminderStatus(fx.reminderIds.get(0))).isEqualTo(ReminderStatus.PENDING);
        assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0))).isEmpty();
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> r != null
                && fx.reservationIds.get(0).equals(r.sourceId())));
        CommitFailer.disarm();
    }

    @Test
    @DisplayName("二重 publish: 同じリマインダーの配送要求が 2 回発火しても、通知行は 1 件・配送は 1 回だけ")
    void reminderDuplicateEventsDeliverOnce() {
        ReminderFx fx = reminderFixture(1);
        ReservationReminderDispatchEvent event = new ReservationReminderDispatchEvent(
                fx.reminderIds.get(0), fx.reservationIds.get(0), fx.teamId, fx.userIds.get(0),
                FUTURE, LocalTime.of(10, 0), "二重");

        // 2 つの項目TXが同じリマインダーを publish した状況（次周回／管理画面の再実行が配送完了前に走った）を再現する。
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(event));
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(event));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0)))
                    .hasSize(1);
            assertThat(reminderStatus(fx.reminderIds.get(0))).isEqualTo(ReminderStatus.SENT);
        });
        await().during(2, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(fx.userIds.get(0), "RESERVATION_REMINDER", "RESERVATION", fx.reservationIds.get(0)))
                        .hasSize(1));
        verify(notificationDeliveryRunner, times(1)).sendOne(argThat(r -> r != null
                && fx.reservationIds.get(0).equals(r.sourceId())));
    }

    @Test
    @DisplayName("リマインド: 配送失敗でも印を戻す処理が失敗したら ERROR ログのみで、SENT が残り再送されない")
    void reminderReleaseFailureLeavesSent() {
        ReminderFx fx = reminderFixture(1);
        willThrow(new RuntimeException("模擬通知配送失敗")).given(notificationDeliveryRunner).sendOne(any());
        willThrow(new RuntimeException("模擬印戻し失敗")).given(reminderRunner).release(any(), any());

        reminderBatch.dispatchDueReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(reminderRunner, times(1)).release(eq(fx.reminderIds.get(0)), any()));
        assertThat(reminderStatus(fx.reminderIds.get(0))).as("戻せなかったので先取りした印が残る")
                .isEqualTo(ReminderStatus.SENT);
    }

    @Test
    @DisplayName("待ち: 配送失敗でも印を戻す処理が失敗したら ERROR ログのみで、他の行の配送は続き、失敗行の印は残る")
    void waitlistReleaseFailureDoesNotPropagate() {
        WaitFx fx = waitFixture(2);
        Long failedUser = fx.userIds.get(0);
        failFor(failedUser);
        willThrow(new RuntimeException("模擬印戻し失敗")).given(waitlistNotifyRunner).release(any(), any(), any());

        publishReopened(fx);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(waitlistNotifyRunner, times(1)).release(any(), any(), any());
            assertThat(rows(fx.userIds.get(1), "RESERVATION_WAITLIST_OPENING", "RESERVATION", fx.slotId)).hasSize(1);
        });
        assertThat(notifiedAt(fx.slotId, failedUser)).as("戻せなかったので印が残る").isNotNull();
    }

    @Test
    @DisplayName("claim 境界: 抑止期限ちょうど（notified_at == 基準）は取得でき、1 マイクロ秒でも内側なら取得できない。取得後は同じ基準で再取得できない")
    void waitlistClaimBoundary() {
        LocalDateTime notified = LocalDateTime.now(clock).minusHours(2).withNano(250_000_000);
        WaitFx fx = waitFixture(1, notified);
        java.util.UUID entryId = transactionTemplate.execute(tx -> waitlistRepository
                .findBySlotIdAndUserIdAndStatus(fx.slotId, fx.userIds.get(0), WaitlistStatus.WAITING)
                .orElseThrow().getId());
        LocalDateTime claimedAt = LocalDateTime.now(clock).withNano(0);

        assertThat(waitlistNotifyRunner.claim(entryId, claimedAt, notified.minusNanos(1_000)))
                .as("基準より 1 マイクロ秒だけ新しく通知済み（抑止期限の内側）なら取れない").isFalse();
        assertThat(waitlistNotifyRunner.claim(entryId, claimedAt, notified))
                .as("抑止期限ちょうど（60 分）は通知する側").isTrue();
        assertThat(waitlistNotifyRunner.claim(entryId, claimedAt.plusSeconds(1), notified))
                .as("取得後は同じ基準で再取得できない（二重 claim なし）").isFalse();
        assertThat(notifiedAt(fx.slotId, fx.userIds.get(0))).isEqualTo(claimedAt);
        // updated_at は既存の生成・更新と同じ基準（JVM 既定ゾーンの壁時計）で入る。notified_at の基準（UTC Clock）と混ぜない。
        LocalDateTime updatedAt = transactionTemplate.execute(tx -> waitlistRepository.findById(entryId).orElseThrow().getUpdatedAt());
        assertThat(updatedAt).as("updated_at は JVM 既定ゾーン基準").isBetween(
                LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE).minusMinutes(5), LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE).plusMinutes(5));
        assertThat(java.time.Duration.between(updatedAt, claimedAt).abs())
                .as("notified_at(UTC) と updated_at(JST) は別基準なので同じ値にならない").isGreaterThan(java.time.Duration.ofHours(1));
    }

    @Test
    @DisplayName("release: 照合キー（claimedAt）が一致しないときは何も戻さない")
    void waitlistReleaseWithMismatchedClaimKeyDoesNothing() {
        WaitFx fx = waitFixture(1);
        java.util.UUID entryId = transactionTemplate.execute(tx -> waitlistRepository
                .findBySlotIdAndUserIdAndStatus(fx.slotId, fx.userIds.get(0), WaitlistStatus.WAITING)
                .orElseThrow().getId());
        LocalDateTime claimedAt = LocalDateTime.now(clock).withNano(0);
        assertThat(waitlistNotifyRunner.claim(entryId, claimedAt, claimedAt.minusMinutes(60))).isTrue();

        waitlistNotifyRunner.release(entryId, claimedAt.minusSeconds(5), null);
        assertThat(notifiedAt(fx.slotId, fx.userIds.get(0))).as("別の claim の戻しは効かない").isEqualTo(claimedAt);

        waitlistNotifyRunner.release(entryId, claimedAt, null);
        assertThat(notifiedAt(fx.slotId, fx.userIds.get(0))).as("自分の claim は prev に戻る").isNull();
    }

    /**
     * publish 後・コミット前に例外を投げ、項目TXのコミットを失敗させる検証用リスナー（AC-A: publish 済みでも通知が出ない）。
     * 対象リマインダーIDを指定したときだけ作動する。
     */
    @org.springframework.boot.test.context.TestComponent
    static class CommitFailer {
        private static volatile Long targetReminderId;
        private static volatile boolean closureArmed;
        private static volatile Long targetSlotId;

        static void armFor(Long reminderId) {
            targetReminderId = reminderId;
        }

        static void armClosure() {
            closureArmed = true;
        }

        static void armSlot(Long slotId) {
            targetSlotId = slotId;
        }

        static void disarm() {
            targetReminderId = null;
            closureArmed = false;
            targetSlotId = null;
        }

        @org.springframework.transaction.event.TransactionalEventListener(
                phase = org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT)
        void onBeforeCommit(ReservationReminderDispatchEvent event) {
            if (event.reminderId().equals(targetReminderId)) {
                throw new IllegalStateException("模擬コミット失敗（BEFORE_COMMIT）");
            }
        }

        @org.springframework.transaction.event.TransactionalEventListener(
                phase = org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT)
        void onBeforeCommit(EmergencyClosureNotificationEvent event) {
            if (closureArmed) {
                throw new IllegalStateException("模擬コミット失敗（BEFORE_COMMIT）");
            }
        }

        @org.springframework.transaction.event.TransactionalEventListener(
                phase = org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT)
        void onBeforeCommit(ReservationSlotReopenedEvent event) {
            if (event.getSlotId().equals(targetSlotId)) {
                throw new IllegalStateException("模擬コミット失敗（BEFORE_COMMIT）");
            }
        }
    }

    // ═════════════════════════════════════════════
    // フィクスチャ・観測
    // ═════════════════════════════════════════════

    private record ClosureFx(Long teamId, Long operatorId, LocalDate date, List<Long> userIds,
                             List<Long> reservationIds) {}

    private record ReminderFx(Long teamId, List<Long> userIds, List<Long> reservationIds, List<Long> reminderIds) {}

    private record WaitFx(Long teamId, Long slotId, List<Long> userIds) {}

    private static Long next() {
        return SEQ.incrementAndGet();
    }

    /** 同じ日の別時間帯に枠を {@code count} 個作り、それぞれにユーザーを1人ずつ予約させる（コミット済み）。 */
    private ClosureFx closureFixture(int count) {
        return transactionTemplate.execute(tx -> {
            Long teamId = next();
            LocalDate date = FUTURE.plusDays(next() % 300);
            List<Long> users = new ArrayList<>();
            List<Long> reservations = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Long userId = next();
                ReservationSlotEntity slot = slotRepository.save(ReservationSlotEntity.builder()
                        .teamId(teamId).title("休業枠" + i).slotDate(date)
                        .startTime(LocalTime.of(8 + i, 0)).endTime(LocalTime.of(8 + i, 30))
                        .capacity(1).bookedCount(1).slotStatus(SlotStatus.FULL).build());
                ReservationEntity reservation = reservationRepository.save(ReservationEntity.builder()
                        .reservationSlotId(slot.getId()).lineId(1L).teamId(teamId).userId(userId)
                        .status(ReservationStatus.CONFIRMED).build());
                users.add(userId);
                reservations.add(reservation.getId());
            }
            return new ClosureFx(teamId, next(), date, users, reservations);
        });
    }

    private CreateEmergencyClosureRequest closureRequest(ClosureFx fx, boolean cancel) {
        CreateEmergencyClosureRequest request = new CreateEmergencyClosureRequest();
        request.setStartDate(fx.date);
        request.setEndDate(fx.date);
        request.setReason("先生体調不良");
        request.setSubject("臨時休業のお知らせ");
        request.setMessageBody("本日は休業いたします。");
        request.setCancelReservations(cancel);
        return request;
    }

    private EmergencyClosureResponse sendClosure(ClosureFx fx, boolean cancel) {
        return closureService.sendClosure(fx.teamId, fx.operatorId, closureRequest(fx, cancel));
    }

    private LocalDateTime dueAt() {
        return LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE).minusHours(1);
    }

    /** 期限到来済みの PENDING リマインダーと、その予約・枠を {@code count} 組作る（コミット済み）。 */
    private ReminderFx reminderFixture(int count) {
        return transactionTemplate.execute(tx -> {
            Long teamId = next();
            List<Long> users = new ArrayList<>();
            List<Long> reservations = new ArrayList<>();
            List<Long> reminders = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Long userId = next();
                ReservationSlotEntity slot = slotRepository.save(ReservationSlotEntity.builder()
                        .teamId(teamId).title("リマインド枠" + i).slotDate(FUTURE.plusDays(i))
                        .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
                        .capacity(1).bookedCount(1).slotStatus(SlotStatus.FULL).build());
                ReservationEntity reservation = reservationRepository.save(ReservationEntity.builder()
                        .reservationSlotId(slot.getId()).lineId(1L).teamId(teamId).userId(userId)
                        .status(ReservationStatus.CONFIRMED).build());
                ReservationReminderEntity reminder = reminderRepository.save(ReservationReminderEntity.builder()
                        .reservationId(reservation.getId()).remindAt(dueAt()).build());
                users.add(userId);
                reservations.add(reservation.getId());
                reminders.add(reminder.getId());
            }
            return new ReminderFx(teamId, users, reservations, reminders);
        });
    }

    private WaitFx waitFixture(int waiting) {
        return waitFixture(waiting, null);
    }

    private WaitFx waitFixture(int waiting, LocalDateTime prevNotifiedAt) {
        return waitFixture(waiting, prevNotifiedAt, SlotStatus.AVAILABLE);
    }

    /** 空き復帰済みの枠と、WAITING 登録者 {@code waiting} 名を作る（コミット済み）。 */
    private WaitFx waitFixture(int waiting, LocalDateTime prevNotifiedAt, SlotStatus slotStatus) {
        return transactionTemplate.execute(tx -> {
            Long teamId = next();
            ReservationSlotEntity slot = slotRepository.save(ReservationSlotEntity.builder()
                    .teamId(teamId).title("待ち枠").slotDate(FUTURE.plusDays(next() % 300))
                    .startTime(LocalTime.of(14, 0)).endTime(LocalTime.of(14, 30))
                    .capacity(1).bookedCount(slotStatus == SlotStatus.FULL ? 1 : 0).slotStatus(slotStatus).build());
            List<Long> users = new ArrayList<>();
            for (int i = 0; i < waiting; i++) {
                Long userId = next();
                waitlistRepository.save(ReservationWaitlistEntryEntity.builder()
                        .teamId(teamId).slotId(slot.getId()).userId(userId).status(WaitlistStatus.WAITING)
                        .notifiedAt(prevNotifiedAt).build());
                users.add(userId);
            }
            return new WaitFx(teamId, slot.getId(), users);
        });
    }

    private void publishReopened(WaitFx fx) {
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
                new ReservationSlotReopenedEvent(fx.teamId, fx.slotId)));
    }

    private void failFor(Long failedUser) {
        willAnswer(inv -> {
            NotificationDeliveryRequest req = inv.getArgument(0);
            if (failedUser.equals(req.recipientUserId())) {
                throw new RuntimeException("模擬通知配送失敗（受信者 1 名のみ）");
            }
            return inv.callRealMethod();
        }).given(notificationDeliveryRunner).sendOne(any());
    }

    /** 呼び出し元のTXに参加せず、必ず独立にコミットするテンプレート（配送スレッドの sendOne 内から書くとき用）。 */
    private TransactionTemplate requiresNewTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionTemplate.getTransactionManager());
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private ReminderStatus reminderStatus(Long reminderId) {
        return transactionTemplate.execute(tx -> reminderRepository.findById(reminderId).orElseThrow().getStatus());
    }

    private LocalDateTime notifiedAt(Long slotId, Long userId) {
        return transactionTemplate.execute(tx -> waitlistRepository
                .findBySlotIdAndUserIdAndStatus(slotId, userId, WaitlistStatus.WAITING)
                .orElseThrow().getNotifiedAt());
    }

    private List<NotificationEntity> rows(Long userId, String type, String sourceType, Long sourceId) {
        return transactionTemplate.execute(tx -> notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).getContent().stream()
                .filter(n -> type.equals(n.getNotificationType())
                        && sourceType.equals(n.getSourceType())
                        && sourceId.equals(n.getSourceId()))
                .toList());
    }

    private long statementCount() {
        org.hibernate.stat.Statistics stats = em.getEntityManagerFactory()
                .unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        return stats.getPrepareStatementCount();
    }

    /**
     * event-pool を確実に満杯にする。実 Bean から maxPoolSize とキュー容量を取り、
     * 「max 本のワーカー全員がブロッカーを掴んだ」ことと「キューが容量ぴったりで残容量 0」を確認してから戻る。
     * ThreadPoolExecutor は core を超えるワーカーをキューが満杯になって初めて起こすため、投入数は
     * {@code max + キュー容量} ぴったり（走るのは max 本、キューに積まれるのは容量ぶん）。
     * 「最初の拒否で止める」方式は、再利用待ちのワーカーがキューを取り出す前の一時的な拒否で止まり得るため使わない。
     */
    private void saturateEntirePool(CountDownLatch release) throws InterruptedException {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        java.util.concurrent.ThreadPoolExecutor executor = pool.getThreadPoolExecutor();
        int max = executor.getMaximumPoolSize();
        int queueCapacity = executor.getQueue().remainingCapacity() + executor.getQueue().size();
        CountDownLatch workersBlocked = new CountDownLatch(max);
        for (int i = 0; i < max + queueCapacity; i++) {
            // 1 本でも拒否されたら前提（空のプール）が崩れている。例外はそのままテスト失敗にする。
            eventPool.execute(() -> {
                workersBlocked.countDown();
                awaitQuietly(release);
            });
        }
        assertThat(workersBlocked.await(30, TimeUnit.SECONDS))
                .as("max=%d 本の全ワーカーがブロッカーを掴んだ（active=%d poolSize=%d queued=%d）", max,
                        pool.getActiveCount(), pool.getPoolSize(), executor.getQueue().size())
                .isTrue();
        assertThat(pool.getActiveCount()).as("active == max").isEqualTo(max);
        assertThat(executor.getQueue().remainingCapacity()).as("キューの残容量 0").isZero();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(600, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
