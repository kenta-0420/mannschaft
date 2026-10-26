package com.mannschaft.app.shift.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.event.ShiftPreferenceReminderKind;
import com.mannschaft.app.shift.event.ShiftPreferenceReminderNotificationEvent;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.team.entity.TeamShiftSettingsEntity;
import com.mannschaft.app.team.repository.TeamShiftSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link ShiftPreferenceReminderBatchService} のユニットテスト。F03.5 Phase 4-0 不整合 #B 補修。
 *
 * <p>Issue #2997（CMP-260827-1152 G6）: 通知は業務TXの外（{@code AFTER_COMMIT} リスナー）へ移ったため、
 * 本クラスは「オーケストレータが項目TX（{@link ShiftPreferenceReminderRunner}）を呼ぶこと」と
 * 「手動リマインドが配送要求を publish すること」を固定する。配送の意味論（文面・locale・遷移先）は
 * {@code ShiftPreferenceReminderNotificationListenerTest}、印の契約は {@code ShiftPreferenceReminderRunnerTest}、
 * TX 境界は {@code ShiftReminderNotificationTransactionIT} が持つ。</p>
 */
@ExtendWith(MockitoExtension.class)
class ShiftPreferenceReminderBatchServiceTest {

    @Mock private ShiftScheduleRepository scheduleRepository;
    @Mock private ShiftRequestRepository requestRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private TeamShiftSettingsRepository teamShiftSettingsRepository;
    @Mock private AuditLogService auditLogService;
    // Phase 11 事後検分 fixup（2026-05-19）: triggerManualReminder の Valkey 連打防止ロック用 Mock。
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ShiftPreferenceReminderRunner reminderRunner;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ShiftPreferenceReminderBatchService batchService;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long USER_A = 101L;
    private static final Long USER_B = 102L;
    private static final Long USER_C = 103L;

    // =========================================================
    // 48h リマインド
    // =========================================================

    @Nested
    @DisplayName("48h リマインド")
    class Remind48h {

        @Test
        @DisplayName("未提出メンバーだけを項目TXへ渡す")
        void 未提出メンバーを項目TXへ渡す() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor48hReminder(any(), any()))
                    .willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            // USER_A が提出済み、USER_B・USER_C は未提出
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(buildRequest(SCHEDULE_ID, USER_A)));
            given(userRoleRepository.findMemberCandidateIdsByTeam(TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B, USER_C));

            batchService.processReminders();

            verify(reminderRunner).remindOne(
                    SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_48H, List.of(USER_B, USER_C));
        }

        @Test
        @DisplayName("全員提出済みでも項目TXを呼ぶ（未提出者が空 = 通知なしで印だけ立てる、是正前と同じ）")
        void 全員提出済みは空リストで項目TX() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor48hReminder(any(), any()))
                    .willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(buildRequest(SCHEDULE_ID, USER_A), buildRequest(SCHEDULE_ID, USER_B)));
            given(userRoleRepository.findMemberCandidateIdsByTeam(TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B));

            batchService.processReminders();

            verify(reminderRunner).remindOne(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_48H, List.of());
        }

        @Test
        @DisplayName("チームが 48h リマインドを無効にしていたらスキップする")
        void 設定で無効ならスキップ() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor48hReminder(any(), any())).willReturn(List.of(schedule));
            TeamShiftSettingsEntity settings = mock(TeamShiftSettingsEntity.class);
            given(settings.isReminder48hEnabled()).willReturn(false);
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(settings));

            batchService.processReminders();

            verifyNoInteractions(reminderRunner);
        }

        @Test
        @DisplayName("項目TXが例外でも他のスケジュールの処理は続行され、バッチ全体は例外を投げない")
        void 一件の例外で他のスケジュールが巻き込まれない() {
            ShiftScheduleEntity first = buildSchedule(SCHEDULE_ID, TEAM_ID);
            ShiftScheduleEntity second = buildSchedule(2L, TEAM_ID);
            given(scheduleRepository.findFor48hReminder(any(), any())).willReturn(List.of(first, second));
            given(scheduleRepository.findFor24hReminder(any(), any())).willReturn(List.of());
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(anyLong())).willReturn(List.of());
            given(userRoleRepository.findMemberCandidateIdsByTeam(TEAM_ID)).willReturn(List.of(USER_A));
            given(reminderRunner.remindOne(eq(SCHEDULE_ID), any(), any()))
                    .willThrow(new RuntimeException("項目TX失敗"));

            batchService.processReminders();

            verify(reminderRunner).remindOne(eq(2L), eq(ShiftPreferenceReminderKind.REMINDER_48H), any());
        }
    }

    // =========================================================
    // 24h リマインド
    // =========================================================

    @Nested
    @DisplayName("24h リマインド")
    class Remind24h {

        @Test
        @DisplayName("未提出メンバーを 24h の項目TXへ渡す")
        void 未提出メンバーを項目TXへ渡す() {
            given(scheduleRepository.findFor48hReminder(any(), any())).willReturn(List.of());
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor24hReminder(any(), any()))
                    .willReturn(List.of(schedule));
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.empty());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of());
            given(userRoleRepository.findMemberCandidateIdsByTeam(TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B));

            batchService.processReminders();

            verify(reminderRunner).remindOne(
                    SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_24H, List.of(USER_A, USER_B));
        }

        @Test
        @DisplayName("チームが 24h リマインドを無効にしていたらスキップする")
        void 設定で無効ならスキップ() {
            given(scheduleRepository.findFor48hReminder(any(), any())).willReturn(List.of());
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findFor24hReminder(any(), any())).willReturn(List.of(schedule));
            TeamShiftSettingsEntity settings = mock(TeamShiftSettingsEntity.class);
            given(settings.isReminder24hEnabled()).willReturn(false);
            given(teamShiftSettingsRepository.findByTeamId(TEAM_ID)).willReturn(Optional.of(settings));

            batchService.processReminders();

            verifyNoInteractions(reminderRunner);
        }
    }

    // =========================================================
    // 手動リマインド (Phase 11 第二陣 2-α)
    // =========================================================

    /**
     * 手動リマインドの認可（越境は不在と同じ 404・権限不足は 403・SYSTEM_ADMIN 短絡）は tx の外の
     * {@code ShiftScheduleFacade#remindUnsubmitted} へ移した（CMP-260923-0954 W6a）。その検証は
     * {@code ShiftScheduleFacadeTest} と {@code ShiftScheduleSlotFacadeContractIT} が持つ。
     * 本クラスの {@code triggerManualReminder} は認可の後の tx 本体（Valkey の連打防止ロック → 読み直し →
     * 状態判定 → 配送要求の publish → 監査ログ）を固定する。
     */
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
        @DisplayName("COLLECTING_未提出者がいる_配送要求publish+監査ログ記録")
        void COLLECTING_未提出者がいる_配送要求_監査ログ記録() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(buildRequest(SCHEDULE_ID, USER_A)));
            given(userRoleRepository.findMemberCandidateIdsByTeam(TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B, USER_C));

            ManualRemindResponse response = batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID);

            assertThat(response.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(response.getRemindedCount()).isEqualTo(2);
            assertThat(response.getRemindedUserIds()).containsExactlyInAnyOrder(USER_B, USER_C);

            ArgumentCaptor<ShiftPreferenceReminderNotificationEvent> captor =
                    ArgumentCaptor.forClass(ShiftPreferenceReminderNotificationEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().kind()).isEqualTo(ShiftPreferenceReminderKind.MANUAL);
            assertThat(captor.getValue().scheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(captor.getValue().teamId()).isEqualTo(TEAM_ID);
            assertThat(captor.getValue().recipientUserIds()).containsExactly(USER_B, USER_C);
            verify(auditLogService).record(
                    eq("SHIFT_MANUAL_REMINDER_SENT"),
                    eq(OPERATOR_ID), isNull(), eq(TEAM_ID), isNull(),
                    isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("COLLECTING_全員提出済み_配送要求なしでもレスポンスは返り監査ログは残す")
        void COLLECTING_全員提出済み_配送要求なし_監査ログ残す() {
            ShiftScheduleEntity schedule = buildSchedule(SCHEDULE_ID, TEAM_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(
                            buildRequest(SCHEDULE_ID, USER_A),
                            buildRequest(SCHEDULE_ID, USER_B),
                            buildRequest(SCHEDULE_ID, USER_C)));
            given(userRoleRepository.findMemberCandidateIdsByTeam(TEAM_ID))
                    .willReturn(List.of(USER_A, USER_B, USER_C));

            ManualRemindResponse response = batchService.triggerManualReminder(SCHEDULE_ID, OPERATOR_ID);

            assertThat(response.getRemindedCount()).isZero();
            assertThat(response.getRemindedUserIds()).isEmpty();
            verifyNoInteractions(eventPublisher);
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
            verifyNoInteractions(eventPublisher, auditLogService);
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
            verifyNoInteractions(eventPublisher, auditLogService);
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

            // スケジュール取得・配送要求・監査ログのいずれも到達しない（ロック失敗で即時短絡）
            verifyNoInteractions(scheduleRepository, eventPublisher, auditLogService);
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
            verifyNoInteractions(scheduleRepository, eventPublisher, auditLogService);
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
}
