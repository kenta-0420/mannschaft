package com.mannschaft.app.shift.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.mannschaft.app.team.repository.TeamShiftSettingsRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link ShiftPreferenceReminderBatchService} のユニットテスト。F03.5 Phase 4-0 不整合 #B 補修。
 */
@ExtendWith(MockitoExtension.class)
class ShiftPreferenceReminderBatchServiceTest {

    @Mock private ShiftScheduleRepository scheduleRepository;
    @Mock private ShiftRequestRepository requestRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private NotificationHelper notificationHelper;
    @Mock private TeamShiftSettingsRepository teamShiftSettingsRepository;
    @Mock private AuditLogService auditLogService;
    // Phase 11 事後検分 fixup（2026-05-19）: triggerManualReminder の Valkey 連打防止ロック用 Mock。
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private AccessControlService accessControlService;

    /**
     * Issue #2715 CMP-055 ロットC-4: 実物の MessageSource を使う（モックが引数をそのまま返す形だと
     * 鍵の欠落もフォーマット崩れも検出できないため）。@InjectMocks は @Mock フィールドのみを見るため、
     * 実物 MessageSource を渡すコンストラクタ手動組み立てへ切り替える。
     */
    private ResourceBundleMessageSource messageSource;

    private ShiftPreferenceReminderBatchService batchService;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long USER_A = 101L;
    private static final Long USER_B = 102L;
    private static final Long USER_C = 103L;
    private static final Pattern JAPANESE_CHAR = Pattern.compile("[ぁ-ゖァ-ヶ一-龠]");

    @BeforeEach
    void setUpMessageSource() {
        messageSource = new ResourceBundleMessageSource();
        messageSource.setBasenames("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(false);

        batchService = new ShiftPreferenceReminderBatchService(
                scheduleRepository, requestRepository, userRoleRepository,
                notificationHelper, teamShiftSettingsRepository, auditLogService,
                redisTemplate, accessControlService, messageSource);
    }

    // =========================================================
    // 48h リマインド
    // =========================================================

    @Nested
    @DisplayName("48h リマインド")
    class Remind48h {

        @Test
        @DisplayName("未提出メンバーのみに通知を送信し、フラグを更新する")
        void 未提出メンバーに通知_フラグ更新() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor48hReminder(any(), any()))
                    .willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            // USER_A が提出済み、USER_B・USER_C は未提出
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(buildRequest(SCHEDULE_ID, USER_A)));
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B, USER_C));

            batchService.processReminders();

            // 未提出の USER_B・USER_C (2名) に notifyAllLocalized が1回呼ばれる
            verify(notificationHelper).notifyAllLocalized(
                    eq(List.of(USER_B, USER_C)),
                    eq("SHIFT_REQUEST_REMINDER_48H"),
                    eq("SHIFT_SCHEDULE"), eq(SCHEDULE_ID),
                    eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                    anyString(), isNull(), any());
            // フラグが更新される
            verify(scheduleRepository).save(schedule);
        }

        @Test
        @DisplayName("全員提出済みの場合は通知しない")
        void 全員提出済みは通知なし() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor48hReminder(any(), any()))
                    .willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(buildRequest(SCHEDULE_ID, USER_A), buildRequest(SCHEDULE_ID, USER_B)));
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B));

            batchService.processReminders();

            verify(notificationHelper, never()).notifyAllLocalized(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("通知例外発生時はフラグをセットせず次回再試行")
        void 例外発生時はフラグ未更新() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor48hReminder(any(), any()))
                    .willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of());
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of(USER_A));
            doThrow(new RuntimeException("通知エラー")).when(notificationHelper)
                    .notifyAllLocalized(any(), any(), any(), any(), any(), any(), any(), any(), any());

            batchService.processReminders();

            // save（フラグ更新）は呼ばれない
            verify(scheduleRepository, never()).save(any());
        }
    }

    // =========================================================
    // 24h リマインド
    // =========================================================

    @Nested
    @DisplayName("24h リマインド")
    class Remind24h {

        @Test
        @DisplayName("未提出メンバーに 24h 通知を送信し、フラグを更新する")
        void 未提出メンバーに通知_フラグ更新() {
            given(scheduleRepository.findFor48hReminder(any(), any())).willReturn(List.of());
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor24hReminder(any(), any()))
                    .willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of());
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B));

            batchService.processReminders();

            verify(notificationHelper).notifyAllLocalized(
                    eq(List.of(USER_A, USER_B)),
                    eq("SHIFT_REQUEST_REMINDER"),
                    eq("SHIFT_SCHEDULE"), eq(SCHEDULE_ID),
                    eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                    anyString(), isNull(), any());
            verify(scheduleRepository).save(schedule);
        }
    }

    // =========================================================
    // 手動リマインド (Phase 11 第二陣 2-α)
    // =========================================================

    @Nested
    @DisplayName("triggerManualReminder")
    class TriggerManualReminder {

        private static final Long OPERATOR_ID = 999L;

        /**
         * Phase 11 事後検分 fixup（2026-05-19）:
         * 手動リマインドは最初に Valkey の SET NX EX で連打防止ロックを取得する。
         * 個別ケース（連打スロットリングテスト）以外は Lock 取得成功（TRUE）が前提。
         */
        @BeforeEach
        void setUpValkeyLockSuccess() {
            given(redisTemplate.opsForValue()).willReturn(valueOps);
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .willReturn(Boolean.TRUE);
        }

        @Test
        @DisplayName("COLLECTING_未提出者がいる_通知送信+監査ログ記録")
        void COLLECTING_未提出者がいる_通知送信_監査ログ記録() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(buildRequest(SCHEDULE_ID, USER_A)));
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B, USER_C));

            ManualRemindResponse response = batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID);

            assertThat(response.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(response.getRemindedCount()).isEqualTo(2);
            assertThat(response.getRemindedUserIds()).containsExactlyInAnyOrder(USER_B, USER_C);

            verify(notificationHelper).notifyAllLocalized(
                    eq(List.of(USER_B, USER_C)),
                    eq("SHIFT_REQUEST_REMINDER_MANUAL"),
                    eq("SHIFT_SCHEDULE"), eq(SCHEDULE_ID),
                    eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                    anyString(), isNull(), any());
            verify(auditLogService).record(
                    eq("SHIFT_MANUAL_REMINDER_SENT"),
                    eq(OPERATOR_ID), isNull(), eq(TEAM_ID), isNull(),
                    isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("COLLECTING_全員提出済み_通知送信なしでもレスポンスは返り監査ログは残す")
        void COLLECTING_全員提出済み_通知送信なし_監査ログ残す() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(
                            buildRequest(SCHEDULE_ID, USER_A),
                            buildRequest(SCHEDULE_ID, USER_B),
                            buildRequest(SCHEDULE_ID, USER_C)));
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B, USER_C));

            ManualRemindResponse response = batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID);

            assertThat(response.getRemindedCount()).isZero();
            assertThat(response.getRemindedUserIds()).isEmpty();
            verifyNoInteractions(notificationHelper);
            verify(auditLogService).record(
                    eq("SHIFT_MANUAL_REMINDER_SENT"),
                    eq(OPERATOR_ID), isNull(), eq(TEAM_ID), isNull(),
                    isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("スケジュールが存在しない場合は SHIFT_SCHEDULE_NOT_FOUND を投げる")
        void スケジュール非存在_例外() {
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verifyNoInteractions(notificationHelper, auditLogService);
        }

        @Test
        @DisplayName("DRAFT 状態では INVALID_SCHEDULE_STATUS を投げる")
        void DRAFT_状態_例外() {
            ShiftScheduleEntity schedule = ShiftScheduleEntity.builder()
                    .teamId(TEAM_ID)
                    .title("テスト")
                    .status(ShiftScheduleStatus.DRAFT)
                    .endDate(LocalDate.now().plusDays(7))
                    .build();
            ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

            assertThatThrownBy(() -> batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
            verifyNoInteractions(notificationHelper, auditLogService);
        }

        @Test
        @DisplayName("非権限者（当該チームの ADMIN でない）_COMMON_002 で遮断")
        void 非権限者_COMMON_002() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_002);
            // 認可で弾かれるため通知・監査ログは発生しない
            verifyNoInteractions(notificationHelper, auditLogService);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN_短絡でチーム ADMIN チェックを経ずに通過")
        void SYSTEM_ADMIN_短絡で通過() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(true);
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(buildRequest(SCHEDULE_ID, USER_A)));
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B));

            ManualRemindResponse response = batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID);

            assertThat(response.getScheduleId()).isEqualTo(SCHEDULE_ID);
            // SYSTEM_ADMIN はチーム ADMIN チェックを経由しない
            verify(accessControlService, never()).checkAdminOrAbove(anyLong(), anyLong(), anyString());
        }
    }

    // =========================================================
    // 手動リマインド スロットリング（Phase 11 事後検分 fixup / Valkey ロック）
    // =========================================================

    @Nested
    @DisplayName("triggerManualReminder（連打防止ロック）")
    class TriggerManualReminderThrottling {

        private static final Long OPERATOR_ID = 999L;

        @Test
        @DisplayName("Valkey ロック取得失敗（15 秒以内の連打）_MANUAL_REMINDER_THROTTLED を投げる")
        void 連打検出_MANUAL_REMINDER_THROTTLED_スケジュール取得もしない() {
            // Lock 取得失敗（既に他のリクエストが取得済み）を再現
            given(redisTemplate.opsForValue()).willReturn(valueOps);
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .willReturn(Boolean.FALSE);

            assertThatThrownBy(() -> batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ShiftErrorCode.MANUAL_REMINDER_THROTTLED);

            // スケジュール取得・通知・監査ログのいずれも到達しない（ロック失敗で即時短絡）
            verifyNoInteractions(scheduleRepository, notificationHelper, auditLogService);
        }

        @Test
        @DisplayName("Valkey ロック取得 null 返却（Valkey 接続異常時の保守的扱い）_MANUAL_REMINDER_THROTTLED")
        void Lock取得null_保守的にTHROTTLED扱い() {
            // setIfAbsent は connection 喪失等で null を返すことがある。Service は
            // Boolean.TRUE.equals(...) で判定するため null は失敗扱いになる。
            given(redisTemplate.opsForValue()).willReturn(valueOps);
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .willReturn(null);

            assertThatThrownBy(() -> batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ShiftErrorCode.MANUAL_REMINDER_THROTTLED);
            verifyNoInteractions(scheduleRepository, notificationHelper, auditLogService);
        }
    }

    // =========================================================
    // ヘルパー
    // =========================================================

    private ShiftScheduleEntity buildSchedule(Long id, Long teamId) {
        ShiftScheduleEntity entity = ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("テストシフト")
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().plusHours(30))
                .endDate(LocalDate.now().plusDays(7))
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private ShiftRequestEntity buildRequest(Long scheduleId, Long userId) {
        return ShiftRequestEntity.builder()
                .scheduleId(scheduleId)
                .userId(userId)
                .slotDate(LocalDate.now())
                .build();
    }

    // =========================================================
    // Issue #2715 CMP-055 ロットC-4: 通知本文の i18n
    // =========================================================

    @Nested
    @DisplayName("通知本文の i18n (Issue #2715 CMP-055 ロットC-4)")
    class NotificationI18n {

        @Test
        @DisplayName("48h リマインド: en ロケールで件名・本文が英語になりプレースホルダが残らない")
        void reminder48h_en() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor48hReminder(any(), any())).willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID)).willReturn(List.of());
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(USER_A));

            batchService.processReminders();

            org.mockito.ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> captor =
                    org.mockito.ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
            verify(notificationHelper).notifyAllLocalized(
                    any(), eq("SHIFT_REQUEST_REMINDER_48H"),
                    any(), any(), any(), any(), any(), any(), captor.capture());

            NotificationHelper.LocalizedMessage en = captor.getValue().build(USER_A, Locale.ENGLISH);
            assertThat(JAPANESE_CHAR.matcher(en.title()).find()).isFalse();
            // body 中の {0} はスケジュールタイトル（ユーザー入力の日本語）そのものなので、
            // それを除いた「静的な文言部分」に日本語が残っていないことを検証する（AC-7）。
            assertThat(JAPANESE_CHAR.matcher(en.body().replace(schedule.getTitle(), "")).find()).isFalse();
            assertThat(en.title()).isEqualTo("Shift preference submission deadline in 48 hours");
            assertThat(en.body()).contains("テストシフト").doesNotContain("{0}");

            NotificationHelper.LocalizedMessage ja = captor.getValue().build(USER_A, Locale.JAPANESE);
            assertThat(ja.title()).isEqualTo("シフト希望の提出期限 48 時間前です");
            assertThat(ja.body()).contains("テストシフト");
        }

        @Test
        @DisplayName("24h リマインド: en ロケールで件名・本文が英語になりプレースホルダが残らない")
        void reminder24h_en() {
            given(scheduleRepository.findFor48hReminder(any(), any())).willReturn(List.of());
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor24hReminder(any(), any())).willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID)).willReturn(List.of());
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(USER_A));

            batchService.processReminders();

            org.mockito.ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> captor =
                    org.mockito.ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
            verify(notificationHelper).notifyAllLocalized(
                    any(), eq("SHIFT_REQUEST_REMINDER"),
                    any(), any(), any(), any(), any(), any(), captor.capture());

            NotificationHelper.LocalizedMessage en = captor.getValue().build(USER_A, Locale.ENGLISH);
            assertThat(JAPANESE_CHAR.matcher(en.title()).find()).isFalse();
            assertThat(JAPANESE_CHAR.matcher(en.body().replace(schedule.getTitle(), "")).find()).isFalse();
        }

        @Test
        @DisplayName("手動リマインド: en ロケールで件名・本文が英語になりプレースホルダが残らない")
        void manualReminder_en() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(redisTemplate.opsForValue()).willReturn(valueOps);
            given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .willReturn(Boolean.TRUE);
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID)).willReturn(List.of());
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(USER_A));

            batchService.triggerManualReminder(SCHEDULE_ID, 999L);

            org.mockito.ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> captor =
                    org.mockito.ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
            verify(notificationHelper).notifyAllLocalized(
                    any(), eq("SHIFT_REQUEST_REMINDER_MANUAL"),
                    any(), any(), any(), any(), any(), any(), captor.capture());

            NotificationHelper.LocalizedMessage en = captor.getValue().build(USER_A, Locale.ENGLISH);
            assertThat(JAPANESE_CHAR.matcher(en.title()).find()).isFalse();
            assertThat(JAPANESE_CHAR.matcher(en.body().replace(schedule.getTitle(), "")).find()).isFalse();
            assertThat(en.title()).isEqualTo("Reminder to submit your shift preference");
        }

        /**
         * AC-3 番人: 一括通知経路 (notifyAllLocalized) を使う限り、複数受信者ループの外で
         * バルク locale 解決される（内部の UserLocaleCache が担う）。本テストは、
         * サービス側が受信者ごとに UserLocaleCache/MessageSource を直接呼ばないこと
         * （= notifyAllLocalized 1回のみで済むこと）を確認する。
         */
        @Test
        @DisplayName("複数受信者でも notifyAllLocalized は1回のみ呼ばれる（N+1防止）")
        void 複数受信者でもnotifyAllLocalizedは1回() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor48hReminder(any(), any())).willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID)).willReturn(List.of());
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B, USER_C));

            batchService.processReminders();

            verify(notificationHelper, org.mockito.Mockito.times(1)).notifyAllLocalized(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
