package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmedVia;
import com.mannschaft.app.notification.confirmable.event.ConfirmableReminderResendEvent;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #2997（CMP-260827-1152）G4-b — 確認通知の手動リマインド再送({@code resendReminder})の付随通知を
 * 業務トランザクションから切り離したことを実 DB で検証する。
 *
 * <ul>
 *   <li>AC-A2: コミットされたら未確認かつ非除外の受信者全員に通知行ができる。</li>
 *   <li>AC-A: publish 後・commit 前（BEFORE_COMMIT）に強制 rollback すると通知行は 1 件も作られない
 *       （非同期完了は event-pool の drain で待つ。sleep のみは使わない）。</li>
 *   <li>AC-B / AC-B2: 通知配送が失敗しても再送要求は例外にならず（HTTP 204 相当）、1 人の失敗が他に波及しない。
 *       障害は {@link NotificationDeliveryRunner#sendOne} の spy で注入し、<b>呼出回数を await</b> して
 *       注入点に到達した証拠を取る。</li>
 *   <li>対象は未確認かつ非除外の人だけ／TX 外から直接呼んでも配送が消えない／未確認 0 人なら publish しない。</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} は付けない（付けるとコミットが起きず AFTER_COMMIT が発火しない偽の緑になる）。
 * フィクスチャは {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@RecordApplicationEvents
@Import(ConfirmableReminderResendTransactionIT.CommitFailer.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G4-b 確認通知の手動リマインド再送のトランザクション分離の実DB検証")
class ConfirmableReminderResendTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String TYPE = "CONFIRMABLE_NOTIFICATION_REMINDER";
    private static final String SOURCE = "CONFIRMABLE_NOTIFICATION";

    @Autowired private ConfirmableNotificationConfirmService confirmService;
    @Autowired private ConfirmableNotificationRepository notificationRepository;
    @Autowired private ConfirmableNotificationRecipientRepository recipientRepository;
    @Autowired private NotificationRepository inboxRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventPool;
    @Autowired private ApplicationEvents applicationEvents;
    @PersistenceContext private EntityManager em;

    @MockitoSpyBean private NotificationDeliveryRunner notificationDeliveryRunner;
    @MockitoSpyBean private com.mannschaft.app.notification.service.NotificationService notificationService;
    @Autowired private ConfirmableNotificationService facade;

    private String emailPrefix;
    private Long notificationId;

    @AfterEach
    void cleanUp() {
        CommitFailer.disarm();
        drainEventPool();
        if (notificationId != null) {
            transactionTemplate.executeWithoutResult(tx -> {
                recipientRepository.deleteAll(recipientRepository.findByConfirmableNotificationId(notificationId));
                notificationRepository.deleteById(notificationId);
            });
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(transactionManager, em, emailPrefix);
        }
    }

    private void drainEventPool() {
        await().atMost(20, TimeUnit.SECONDS).until(() ->
                eventPool.getActiveCount() == 0 && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
    }

    // ═════════════════════════════════════════════
    // AC-A2 / 対象の絞り込み / TX 外直接呼出
    // ═════════════════════════════════════════════

    @Test
    @DisplayName("AC-A2・TX外直接呼出: コミットされたら未確認かつ非除外の全員に通知行ができ、確認済み・除外済みには届かない")
    void commitCreatesNotificationForUnconfirmedNonExcludedOnly() {
        Fx fx = seed(5, 1, 1); // 未確認5 + 確認済み1 + 除外1

        // 外側のトランザクションを持たない呼出（テストにも @Transactional は無い）。
        assertThatCode(() -> confirmService.resendReminder(fx.notificationId)).doesNotThrowAnyException();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long user : fx.unconfirmed) {
                assertThat(rows(user, fx.notificationId)).as("未確認者全員に通知行").hasSize(1);
            }
        });
        drainEventPool();
        assertThat(rows(fx.confirmed.get(0), fx.notificationId)).as("確認済みには届かない").isEmpty();
        assertThat(rows(fx.excluded.get(0), fx.notificationId)).as("除外済みには届かない").isEmpty();
        List<ConfirmableReminderResendEvent> events = applicationEvents
                .stream(ConfirmableReminderResendEvent.class).toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).recipientUserIds()).containsExactlyInAnyOrderElementsOf(fx.unconfirmed);
    }

    // ═════════════════════════════════════════════
    // AC-A: 業務TXがロールバックしたら通知は出ない
    // ═════════════════════════════════════════════

    @Test
    @DisplayName("AC-A: publish 後・commit 前に失敗させると、配送要求は publish 済みでも通知行は 1 件も作られない")
    void rollbackAfterPublishCreatesNoNotification() {
        Fx fx = seed(3, 0, 0);
        CommitFailer.arm(fx.notificationId);

        assertThatThrownBy(() -> confirmService.resendReminder(fx.notificationId))
                .hasStackTraceContaining("模擬コミット失敗");

        assertThat(applicationEvents.stream(ConfirmableReminderResendEvent.class)
                .filter(e -> fx.notificationId.equals(e.notificationId())).count())
                .as("配送要求は publish 済み（commit 前に失敗させた証拠）").isEqualTo(1L);
        // 非同期配送が走り切る余地を与えたうえで 0 件であること（drain 後に確認する）。
        drainEventPool();
        for (Long user : fx.unconfirmed) {
            assertThat(rows(user, fx.notificationId)).isEmpty();
        }
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> r != null
                && SOURCE.equals(r.sourceType()) && fx.notificationId.equals(r.sourceId())));
    }

    @Test
    @DisplayName("AC-A（外側TX）: 外側トランザクションが resendReminder の後で強制ロールバックされても、通知は作られない")
    void outerTransactionRollbackCreatesNoNotification() {
        Fx fx = seed(3, 0, 0);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            confirmService.resendReminder(fx.notificationId);
            throw new IllegalStateException("強制ロールバック（AC-A検証用）");
        })).isInstanceOf(IllegalStateException.class);

        drainEventPool();
        for (Long user : fx.unconfirmed) {
            assertThat(rows(user, fx.notificationId)).isEmpty();
        }
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("ファサード経由（ConfirmableNotificationService#resendReminder）でもコミット後に全員へ届く")
    void facadeDeliversToEveryone() {
        Fx fx = seed(2, 0, 0);

        facade.resendReminder(fx.notificationId);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long user : fx.unconfirmed) {
                assertThat(rows(user, fx.notificationId)).hasSize(1);
            }
        });
    }

    @Test
    @DisplayName("AC-B（実DB例外）: 通知永続化が実 DB 例外になっても再送要求は正常に返り、他の受信者には届く")
    void realDbErrorForOneRecipientDoesNotPoisonRequest() {
        Fx fx = seed(3, 0, 0);
        Long failing = fx.unconfirmed.get(0);
        // 呼び出された時点の TX 上で実 DB に不正な SQL を流す（旧実装では呼び出し元TXが rollback-only になる）。
        willAnswer(inv -> {
            em.createNativeQuery("INSERT INTO notifications (no_such_column_g4b) VALUES (1)").executeUpdate();
            return null;
        }).given(notificationService).createNotification(eq(failing), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());

        assertThatCode(() -> facade.resendReminder(fx.notificationId)).doesNotThrowAnyException();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(notificationService, times(1)).createNotification(eq(failing), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
            assertThat(rows(fx.unconfirmed.get(1), fx.notificationId)).hasSize(1);
            assertThat(rows(fx.unconfirmed.get(2), fx.notificationId)).hasSize(1);
        });
        drainEventPool();
        assertThat(rows(failing, fx.notificationId)).isEmpty();
    }

    // ═════════════════════════════════════════════
    // AC-B / AC-B2: 通知失敗が業務(204)に波及しない
    // ═════════════════════════════════════════════

    @Test
    @DisplayName("AC-B2: 受信者の 1 人だけ配送が失敗しても再送要求は例外にならず、他の受信者には届く")
    void oneRecipientFailureDoesNotAffectOthersOrTheRequest() {
        Fx fx = seed(3, 0, 0);
        Long failedUser = fx.unconfirmed.get(1);
        failFor(failedUser);

        assertThatCode(() -> confirmService.resendReminder(fx.notificationId)).doesNotThrowAnyException();

        // 失敗を注入した対象への sendOne に実際に到達した証拠（呼出回数）。到達しなければ旧実装（TX 内同期）と区別できない。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(notificationDeliveryRunner, times(1)).sendOne(argThat(r -> r != null
                    && failedUser.equals(r.recipientUserId()) && fx.notificationId.equals(r.sourceId())));
            assertThat(rows(fx.unconfirmed.get(0), fx.notificationId)).hasSize(1);
            assertThat(rows(fx.unconfirmed.get(2), fx.notificationId)).hasSize(1);
        });
        drainEventPool();
        assertThat(rows(failedUser, fx.notificationId)).as("失敗した 1 人には届かない").isEmpty();
        assertThat(notificationRepository.findById(fx.notificationId)).as("確認通知の親行は残る").isPresent();
    }

    @Test
    @DisplayName("AC-B: 全員の配送が失敗しても再送要求は例外にならず、確認通知・受信者行は無傷")
    void allRecipientsFailureStillSucceedsAndKeepsBusinessRows() {
        Fx fx = seed(2, 0, 0);
        willThrow(new RuntimeException("模擬通知配送失敗（AC-B 検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        assertThatCode(() -> confirmService.resendReminder(fx.notificationId)).doesNotThrowAnyException();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(2)).sendOne(argThat(r -> r != null
                        && SOURCE.equals(r.sourceType()) && fx.notificationId.equals(r.sourceId()))));
        drainEventPool();
        assertThat(notificationRepository.findById(fx.notificationId)).isPresent();
        assertThat(recipientRepository.findByConfirmableNotificationId(fx.notificationId)).hasSize(2);
        for (Long user : fx.unconfirmed) {
            assertThat(rows(user, fx.notificationId)).isEmpty();
        }
    }

    @Test
    @DisplayName("AC-B（同一業務TXの更新）: 業務行を更新した同じTXで再送し配送が実DB例外で全滅しても、更新はコミットされて残る")
    void businessUpdateInSameTransactionSurvivesDeliveryFailure() {
        Fx fx = seed(2, 0, 0);
        String updatedTitle = "G4-b 業務更新 " + UUID.randomUUID();
        // 配送の永続化を「呼ばれた時点の TX 上で不正 SQL を流す」形で全員失敗させる。
        // 旧実装（業務TX内で同期配送）ではこの例外が業務TXを rollback-only にし、コミット時に
        // UnexpectedRollbackException となって下の業務更新ごと巻き戻る（＝このテストは赤になる）。
        willAnswer(inv -> {
            em.createNativeQuery("INSERT INTO notifications (no_such_column_g4b) VALUES (1)").executeUpdate();
            return null;
        }).given(notificationService).createNotification(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());

        // テスト側の外側TXで業務行を更新し、同じTXの中で再送する（resendReminder は REQUIRED で参加する）。
        assertThatCode(() -> transactionTemplate.executeWithoutResult(tx -> {
            int updated = jdbcTemplate.update(
                    "UPDATE confirmable_notifications SET title = ? WHERE id = ?", updatedTitle, fx.notificationId);
            assertThat(updated).isEqualTo(1);
            confirmService.resendReminder(fx.notificationId);
        })).as("配送失敗が業務TXのコミットへ波及しない（204 相当）").doesNotThrowAnyException();

        // 注入点（実DB例外を流す createNotification）に全員ぶん到達したことを待つ。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, times(2)).createNotification(any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any()));
        drainEventPool();

        // 別TX（JdbcTemplate の自動コミット接続）から、更新後の値が残っていることを確かめる。
        String persistedTitle = jdbcTemplate.queryForObject(
                "SELECT title FROM confirmable_notifications WHERE id = ?", String.class, fx.notificationId);
        assertThat(persistedTitle).as("同じ業務TXの更新はコミットされて残る").isEqualTo(updatedTitle);
        for (Long user : fx.unconfirmed) {
            assertThat(rows(user, fx.notificationId)).isEmpty();
        }
    }

    @Test
    @DisplayName("deny（VISIBILITY_DENIED）は例外ではなく、再送要求は成功し他の受信者には届く")
    void denyIsNotAnError() {
        Fx fx = seed(2, 0, 0);
        Long deniedUser = fx.unconfirmed.get(0);
        willAnswer(inv -> {
            NotificationDeliveryRequest req = inv.getArgument(0);
            if (deniedUser.equals(req.recipientUserId())) {
                return NotificationDeliveryResult.VISIBILITY_DENIED;
            }
            return inv.callRealMethod();
        }).given(notificationDeliveryRunner).sendOne(any());

        assertThatCode(() -> confirmService.resendReminder(fx.notificationId)).doesNotThrowAnyException();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(fx.unconfirmed.get(1), fx.notificationId)).hasSize(1));
        drainEventPool();
        assertThat(rows(deniedUser, fx.notificationId)).isEmpty();
    }

    // ═════════════════════════════════════════════
    // 境界
    // ═════════════════════════════════════════════

    @Test
    @DisplayName("境界: 未確認者が 0 人なら配送要求を publish せず、例外にもならない")
    void noUnconfirmedRecipientsPublishesNothing() {
        Fx fx = seed(0, 2, 1);

        assertThatCode(() -> confirmService.resendReminder(fx.notificationId)).doesNotThrowAnyException();

        assertThat(applicationEvents.stream(ConfirmableReminderResendEvent.class).count())
                .as("0 人なら配送要求を発行しない").isZero();
        drainEventPool();
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> r != null
                && SOURCE.equals(r.sourceType()) && fx.notificationId.equals(r.sourceId())));
    }

    @Test
    @DisplayName("境界: ACTIVE でない確認通知の再送は拒否され、配送要求は publish されない")
    void cancelledNotificationRejectsAndPublishesNothing() {
        Fx fx = seed(2, 0, 0);
        transactionTemplate.executeWithoutResult(tx -> {
            ConfirmableNotificationEntity n = notificationRepository.findById(fx.notificationId).orElseThrow();
            n.cancel(null);
            notificationRepository.save(n);
        });

        assertThatThrownBy(() -> confirmService.resendReminder(fx.notificationId))
                .isInstanceOf(BusinessException.class);

        assertThat(applicationEvents.stream(ConfirmableReminderResendEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("文面: 通知種別・件名・本文・優先度は是正前と同じ（CONFIRMABLE_NOTIFICATION_REMINDER・確認通知の件名/本文/優先度）")
    void deliveredNotificationKeepsPreviousSemantics() {
        Fx fx = seed(1, 0, 0);

        confirmService.resendReminder(fx.notificationId);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(rows(fx.unconfirmed.get(0), fx.notificationId)).hasSize(1));
        NotificationEntity row = rows(fx.unconfirmed.get(0), fx.notificationId).get(0);
        assertThat(row.getTitle()).isEqualTo("G4-b 再送テスト");
        assertThat(row.getBody()).isEqualTo("再送本文");
        assertThat(row.getPriority().name()).isEqualTo("HIGH");
        assertThat(row.getSourceType()).isEqualTo(SOURCE);
    }

    /**
     * publish 後・コミット前に例外を投げ、再送TXのコミットを失敗させる検証用リスナー（AC-A）。
     * 対象の確認通知IDを指定したときだけ作動する。
     */
    @org.springframework.boot.test.context.TestComponent
    static class CommitFailer {
        private static volatile Long targetNotificationId;

        static void arm(Long notificationId) {
            targetNotificationId = notificationId;
        }

        static void disarm() {
            targetNotificationId = null;
        }

        @org.springframework.transaction.event.TransactionalEventListener(
                phase = org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT)
        void onBeforeCommit(ConfirmableReminderResendEvent event) {
            if (event.notificationId().equals(targetNotificationId)) {
                throw new IllegalStateException("模擬コミット失敗（BEFORE_COMMIT）");
            }
        }
    }

    // ═════════════════════════════════════════════
    // フィクスチャ・観測
    // ═════════════════════════════════════════════

    private record Fx(Long notificationId, List<Long> unconfirmed, List<Long> confirmed, List<Long> excluded) {}

    /** 確認通知 1 件と、未確認・確認済み・除外済みの受信者をコミット済みで作る。 */
    private Fx seed(int unconfirmedCount, int confirmedCount, int excludedCount) {
        emailPrefix = "g4b-resend-" + UUID.randomUUID();
        int total = unconfirmedCount + confirmedCount + excludedCount;
        List<Long> userIds = total == 0 ? List.of()
                : ConfirmableFanoutFixture.insertUsers(transactionManager, em, total, emailPrefix);
        List<Long> unconfirmed = userIds.subList(0, unconfirmedCount);
        List<Long> confirmed = userIds.subList(unconfirmedCount, unconfirmedCount + confirmedCount);
        List<Long> excluded = userIds.subList(unconfirmedCount + confirmedCount, total);

        notificationId = transactionTemplate.execute(tx -> {
            ConfirmableNotificationEntity notification = notificationRepository.save(
                    ConfirmableNotificationEntity.builder()
                            .scopeType(ScopeType.ORGANIZATION)
                            .scopeId(1L)
                            .title("G4-b 再送テスト")
                            .body("再送本文")
                            .priority(ConfirmableNotificationPriority.HIGH)
                            .status(ConfirmableNotificationStatus.ACTIVE)
                            .deliveryStatus(ConfirmableNotificationDeliveryStatus.DELIVERED)
                            .totalRecipientCount(total)
                            .unconfirmedCount(unconfirmedCount)
                            .build());
            for (Long userId : userIds) {
                ConfirmableNotificationRecipientEntity recipient = ConfirmableNotificationRecipientEntity.builder()
                        .confirmableNotification(notification)
                        .user(userRepository.getReferenceById(userId))
                        .confirmToken(UUID.randomUUID().toString())
                        .build();
                if (confirmed.contains(userId)) {
                    recipient.confirm(ConfirmedVia.APP);
                }
                if (excluded.contains(userId)) {
                    recipient.markExcluded(null);
                }
                recipientRepository.save(recipient);
            }
            return notification.getId();
        });
        return new Fx(notificationId, List.copyOf(unconfirmed), List.copyOf(confirmed), List.copyOf(excluded));
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

    /** 別接続で（コミット済みの）通知行を読む。 */
    private List<NotificationEntity> rows(Long userId, Long sourceId) {
        return transactionTemplate.execute(tx -> inboxRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).getContent().stream()
                .filter(n -> TYPE.equals(n.getNotificationType())
                        && SOURCE.equals(n.getSourceType())
                        && sourceId.equals(n.getSourceId()))
                .toList());
    }
}
