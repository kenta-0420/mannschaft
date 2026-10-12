package com.mannschaft.app.shift;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.service.ShiftPreferenceReminderBatchService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #2997（CMP-260827-1152）G6 — {@link ShiftPreferenceReminderBatchService} の通知トランザクション分離を実 DB で検証する。
 *
 * <p>対象: {@code sendReminderToUnsubmittedMembers}（48h / 24h の定期リマインド）と
 * {@code triggerManualReminder}（管理者の手動リマインド）。</p>
 *
 * <ul>
 *   <li>AC-A2: コミットされたら、未提出メンバーに通知行が作られ、送信済みフラグも確定している。</li>
 *   <li>AC-A: 項目TX（フラグ更新）がロールバックしたら、通知行は 1 件も作られない。</li>
 *   <li>AC-B: 通知配送が失敗しても、送信済みフラグ（スケジュールの更新）はコミットされ、バッチは正常に終わる。
 *       障害は {@link NotificationDeliveryRunner#sendOne} を spy で例外化して注入する。
 *       限界: 例外は Runner 境界で注入しており、通知の永続化 SQL そのものを MySQL 上で失敗させてはいない。
 *       ただし是正前の欠陥（通知の例外が業務TXの rollback-only になる）は、Runner が業務TXの外
 *       （AFTER_COMMIT・別スレッド）で呼ばれることで構造的に閉じていることを AC-A と合わせて固定する。</li>
 *   <li>AC-B2: 受信者が複数でも、1 人ぶんの失敗が他の受信者の通知行とフラグに影響しない。</li>
 *   <li>AC-D（送信済みの印 = {@code is_reminder_sent_48h} / {@code is_reminder_sent}。マスター裁可「配送後に印を立てる」）:
 *       項目TXは印を立てず publish のみ。配送リスナーが {@code sendOne} の後に印を立てる。
 *       全員例外失敗なら印なし（再実行で再送）／一部成功なら印あり（再実行で再送しない）／deny でも印あり／
 *       印の保存失敗は ERROR ログのみ（配送済みの行は残り印は立たない）。</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} は付けない（付けるとコミットが起きず AFTER_COMMIT が発火しない偽の緑になる）。
 * フィクスチャは {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G6 シフト希望リマインドの通知トランザクション分離の実DB検証")
@org.springframework.context.annotation.Import(ShiftReminderNotificationTransactionIT.CommitFailer.class)
class ShiftReminderNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Autowired private ShiftPreferenceReminderBatchService batchService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager em;

    @MockitoSpyBean private NotificationDeliveryRunner notificationDeliveryRunner;
    @MockitoSpyBean private ShiftScheduleRepository scheduleRepository;

    @BeforeEach
    void openGate() {
        // processReminders / 配送リスナーは FEATURE_SHIFT_ENABLED のゲート配下。
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
    }

    // ─────────────────────────────────────────────
    // 定期リマインド（48h）。印は「配送後」に立つ（マスター裁可）
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-A2: コミットされたら未提出メンバーに通知行が作られ、配送後に 48h フラグが確定する")
    void commitCreatesNotificationsAndMarksFlag() {
        Fx fx = fixture("a2", 2);

        batchService.processReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long member : fx.memberIds) {
                assertThat(reminderRows(member, fx.scheduleId))
                        .as("未提出メンバー全員に SHIFT_REQUEST_REMINDER_48H の通知行が作られること")
                        .hasSize(1);
            }
            assertThat(flag48h(fx.scheduleId)).as("配送後に送信済みフラグが確定").isTrue();
        });
    }

    @Test
    @DisplayName("AC-A: publish 後に項目TXのコミットが失敗（BEFORE_COMMIT で例外）したら、通知もフラグも残らない")
    void rollbackAfterPublishCreatesNoNotification() {
        Fx fx = fixture("a", 2);
        CommitFailer.armFor(fx.scheduleId);

        batchService.processReminders();

        assertThat(flag48h(fx.scheduleId)).isFalse();
        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long member : fx.memberIds) {
                assertThat(reminderRows(member, fx.scheduleId)).isEmpty();
            }
        });
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> r != null
                && fx.scheduleId.equals(r.sourceId())));
    }

    @Test
    @DisplayName("AC-B / AC-D: 配送が全員例外で失敗したらフラグは立たず、通知行も無い（バッチは正常終了）")
    void allDeliveryFailureLeavesFlagUnset() {
        Fx fx = fixture("b", 2);
        willThrow(new RuntimeException("模擬通知配送失敗（AC-B 検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        batchService.processReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(2)).sendOne(argThat(r -> r != null && fx.scheduleId.equals(r.sourceId()))));
        assertThat(flag48h(fx.scheduleId)).as("全員失敗なので印は立たない（次回再送）").isFalse();
        for (Long member : fx.memberIds) {
            assertThat(reminderRows(member, fx.scheduleId)).isEmpty();
        }
    }

    @Test
    @DisplayName("AC-D: 全員失敗でフラグが無ければ、再実行で再送されて通知行とフラグが揃う")
    void allFailureIsRetriedOnRerun() {
        Fx fx = fixture("d-retry", 2);
        willThrow(new RuntimeException("模擬通知配送失敗")).given(notificationDeliveryRunner).sendOne(any());
        batchService.processReminders();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(2)).sendOne(argThat(r -> r != null && fx.scheduleId.equals(r.sourceId()))));
        assertThat(flag48h(fx.scheduleId)).isFalse();

        // 障害が解消したものとして実処理に戻す。
        willAnswer(inv -> inv.callRealMethod()).given(notificationDeliveryRunner).sendOne(any());
        batchService.processReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long member : fx.memberIds) {
                assertThat(reminderRows(member, fx.scheduleId)).hasSize(1);
            }
            assertThat(flag48h(fx.scheduleId)).isTrue();
        });
    }

    @Test
    @DisplayName("AC-B（G6 固有）: あるスケジュールの配送が全滅しても、別スケジュールのフラグと通知は残る")
    void failureInOneScheduleKeepsOtherSchedules() {
        Fx failing = fixture("b-ng", 1);
        Fx healthy = fixture("b-ok", 1);
        willAnswer(inv -> {
            com.mannschaft.app.notification.service.NotificationDeliveryRequest req = inv.getArgument(0);
            if (failing.scheduleId.equals(req.sourceId())) {
                throw new RuntimeException("模擬通知配送失敗（片方のスケジュールのみ）");
            }
            return inv.callRealMethod();
        }).given(notificationDeliveryRunner).sendOne(any());

        batchService.processReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(reminderRows(healthy.memberIds.get(0), healthy.scheduleId)).hasSize(1);
            assertThat(flag48h(healthy.scheduleId)).as("無事な側は配送後にフラグが立つ").isTrue();
        });
        assertThat(flag48h(failing.scheduleId)).as("全滅した側は立たない").isFalse();
        assertThat(reminderRows(failing.memberIds.get(0), failing.scheduleId)).isEmpty();
    }

    @Test
    @DisplayName("AC-B2 / AC-D: 受信者の 1 人だけ失敗したら、他の受信者の行は作られ、フラグは立つ")
    void oneRecipientFailureStillMarks() {
        Fx fx = fixture("b2", 3);
        Long failedUser = fx.memberIds.get(1);
        failFor(failedUser);

        batchService.processReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(reminderRows(fx.memberIds.get(0), fx.scheduleId)).hasSize(1);
            assertThat(reminderRows(fx.memberIds.get(2), fx.scheduleId)).hasSize(1);
            assertThat(flag48h(fx.scheduleId)).isTrue();
        });
        assertThat(reminderRows(failedUser, fx.scheduleId)).as("失敗した受信者の行は無い").isEmpty();
    }

    @Test
    @DisplayName("AC-D: 一部成功でフラグが立った後に再実行しても、誰にも再送しない")
    void rerunAfterPartialSuccessDoesNotResend() {
        Fx fx = fixture("d-rerun", 2);
        failFor(fx.memberIds.get(1));
        batchService.processReminders();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(reminderRows(fx.memberIds.get(0), fx.scheduleId)).hasSize(1);
            assertThat(flag48h(fx.scheduleId)).isTrue();
        });

        batchService.processReminders();

        await().during(2, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(reminderRows(fx.memberIds.get(0), fx.scheduleId)).hasSize(1));
        verify(notificationDeliveryRunner, times(2)).sendOne(argThat(r -> r != null && fx.scheduleId.equals(r.sourceId())));
    }

    @Test
    @DisplayName("AC-D: 可視性 deny でもフラグは立つ（通知行は無い）")
    void denyStillMarksFlag() {
        Fx fx = fixture("d-deny", 2);
        willReturn(NotificationDeliveryResult.VISIBILITY_DENIED)
                .given(notificationDeliveryRunner).sendOne(any());

        batchService.processReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(flag48h(fx.scheduleId)).isTrue());
        for (Long member : fx.memberIds) {
            assertThat(reminderRows(member, fx.scheduleId)).isEmpty();
        }
    }

    @Test
    @DisplayName("AC-D: フラグの保存が失敗したら ERROR ログのみで、配送済みの通知行は残りフラグは立たない（次回再送されうる）")
    void flagSaveFailureAfterDeliveryLeavesFlagUnset() {
        Fx fx = fixture("d-save", 2);
        willThrow(new RuntimeException("模擬フラグ保存失敗"))
                .given(scheduleRepository).save(argThat(s -> s != null && fx.scheduleId.equals(s.getId())));

        batchService.processReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long member : fx.memberIds) {
                assertThat(reminderRows(member, fx.scheduleId)).hasSize(1);
            }
        });
        assertThat(flag48h(fx.scheduleId)).isFalse();
    }
    // ─────────────────────────────────────────────
    // 手動リマインド
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("AC-A2（手動）: コミットされたら未提出メンバーに通知行が作られ、応答に人数が載る")
    void manualCommitCreatesNotifications() {
        Fx fx = fixture("m-a2", 2);
        stubManualLock();

        ManualRemindResponse response = batchService.triggerManualReminder(fx.scheduleId, fx.adminId);

        assertThat(response.getRemindedCount()).isEqualTo(2);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long member : fx.memberIds) {
                assertThat(manualRows(member, fx.scheduleId)).hasSize(1);
            }
        });
    }

    @Test
    @DisplayName("AC-A（手動）: 呼び出し元 TX がロールバックしたら通知行は作られない")
    void manualRollbackCreatesNoNotification() {
        Fx fx = fixture("m-a", 2);
        stubManualLock();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> {
            batchService.triggerManualReminder(fx.scheduleId, fx.adminId);
            throw new RuntimeException("強制ロールバック（AC-A 検証用: publish 後のロールバック）");
        })).isInstanceOf(RuntimeException.class);

        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            for (Long member : fx.memberIds) {
                assertThat(manualRows(member, fx.scheduleId)).isEmpty();
            }
        });
        verify(notificationDeliveryRunner, never()).sendOne(argThat(r -> r != null && fx.scheduleId.equals(r.sourceId())));
    }

    @Test
    @DisplayName("AC-B（手動）: 通知配送が失敗しても、呼び出しは正常に返り応答の人数は変わらない")
    void manualDeliveryFailureStillReturnsNormally() {
        Fx fx = fixture("m-b", 2);
        stubManualLock();
        willThrow(new RuntimeException("模擬通知配送失敗（AC-B 検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        ManualRemindResponse response = batchService.triggerManualReminder(fx.scheduleId, fx.adminId);

        assertThat(response.getRemindedCount()).isEqualTo(2);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, times(2)).sendOne(argThat(r -> r != null && fx.scheduleId.equals(r.sourceId()))));
        for (Long member : fx.memberIds) {
            assertThat(manualRows(member, fx.scheduleId)).isEmpty();
        }
    }

    @Test
    @DisplayName("AC-B2（手動）: 受信者の 1 人の通知が失敗しても、他の受信者の通知行は作られる")
    void manualOneRecipientFailureDoesNotAffectOthers() {
        Fx fx = fixture("m-b2", 3);
        stubManualLock();
        Long failedUser = fx.memberIds.get(1);
        willAnswer(inv -> {
            com.mannschaft.app.notification.service.NotificationDeliveryRequest req = inv.getArgument(0);
            if (failedUser.equals(req.recipientUserId())) {
                throw new RuntimeException("模擬通知配送失敗（受信者 1 名のみ）");
            }
            return inv.callRealMethod();
        }).given(notificationDeliveryRunner).sendOne(any());

        ManualRemindResponse response = batchService.triggerManualReminder(fx.scheduleId, fx.adminId);

        assertThat(response.getRemindedCount()).isEqualTo(3);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(manualRows(fx.memberIds.get(0), fx.scheduleId)).hasSize(1);
            assertThat(manualRows(fx.memberIds.get(2), fx.scheduleId)).hasSize(1);
        });
        assertThat(manualRows(failedUser, fx.scheduleId)).isEmpty();
    }

    // ─────────────────────────────────────────────
    // フィクスチャ・観測
    // ─────────────────────────────────────────────

    private record Fx(Long scheduleId, Long teamId, Long adminId, List<Long> memberIds) {}

    /** 48h 窓だけに入る COLLECTING スケジュールと、未提出メンバー {@code memberCount} 名を commit 済みで作る。 */
    private Fx fixture(String tag, int memberCount) {
        return transactionTemplate.execute(tx -> {
            String suffix = tag + "-" + System.nanoTime();
            Long teamId = insertTeam("g6-team-" + suffix);
            Long adminId = insertUser("g6-admin-" + suffix + "@example.com");
            java.util.ArrayList<Long> members = new java.util.ArrayList<>();
            for (int i = 0; i < memberCount; i++) {
                Long uid = insertUser("g6-member" + i + "-" + suffix + "@example.com");
                MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER);
                members.add(uid);
            }
            ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title("G6 リマインド " + suffix)
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.now().plusDays(10))
                    .endDate(LocalDate.now().plusDays(16))
                    .status(ShiftScheduleStatus.COLLECTING)
                    .requestDeadline(LocalDateTime.now(JST).plusHours(30))
                    .createdBy(adminId)
                    .build());
            // adminId は操作者 ID としてだけ使う（所属は張らないので未提出者には含まれない）。
            return new Fx(schedule.getId(), teamId, adminId, members);
        });
    }

    private void failFor(Long failedUser) {
        willAnswer(inv -> {
            com.mannschaft.app.notification.service.NotificationDeliveryRequest req = inv.getArgument(0);
            if (failedUser.equals(req.recipientUserId())) {
                throw new RuntimeException("模擬通知配送失敗（受信者 1 名のみ）");
            }
            return inv.callRealMethod();
        }).given(notificationDeliveryRunner).sendOne(any());
    }

    /**
     * publish 後・コミット前に例外を投げ、項目TXのコミットを失敗させる検証用リスナー（AC-A: publish 済みでも通知が出ない）。
     * 対象スケジュールIDを指定したときだけ作動する。
     */
    @org.springframework.boot.test.context.TestComponent
    static class CommitFailer {
        private static volatile Long targetScheduleId;

        static void armFor(Long scheduleId) {
            targetScheduleId = scheduleId;
        }

        @org.springframework.transaction.event.TransactionalEventListener(
                phase = org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT)
        void onBeforeCommit(com.mannschaft.app.shift.event.ShiftPreferenceReminderNotificationEvent event) {
            if (event.scheduleId().equals(targetScheduleId)) {
                throw new IllegalStateException("模擬コミット失敗（BEFORE_COMMIT）");
            }
        }
    }

    private void stubManualLock() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(ops);
        given(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(Boolean.TRUE);
    }

    private boolean flag48h(Long scheduleId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(tx ->
                scheduleRepository.findById(scheduleId).orElseThrow().getIsReminderSent48h()));
    }

    private List<NotificationEntity> reminderRows(Long userId, Long scheduleId) {
        return rows(userId, scheduleId, "SHIFT_REQUEST_REMINDER_48H");
    }

    private List<NotificationEntity> manualRows(Long userId, Long scheduleId) {
        return rows(userId, scheduleId, "SHIFT_REQUEST_REMINDER_MANUAL");
    }

    private List<NotificationEntity> rows(Long userId, Long scheduleId, String type) {
        return transactionTemplate.execute(tx -> notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)).getContent().stream()
                .filter(n -> type.equals(n.getNotificationType())
                        && scheduleId.equals(n.getSourceId()))
                .toList());
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
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
                                + "VALUES (:email, 'G6', 'テスト', 'G6 テスト', 'ACTIVE', "
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


