package com.mannschaft.app.shift.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.mannschaft.app.team.repository.TeamShiftSettingsRepository;
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
 */
@ExtendWith(MockitoExtension.class)
class ShiftPreferenceReminderBatchServiceTest {

    @Mock private ShiftScheduleRepository scheduleRepository;
    @Mock private ShiftRequestRepository requestRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private NotificationHelper notificationHelper;
    @Mock private TeamShiftSettingsRepository teamShiftSettingsRepository;
    @Mock private AuditLogService auditLogService;

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

            // 未提出の USER_B・USER_C (2名) に notifyAll が1回呼ばれる
            verify(notificationHelper).notifyAll(
                    eq(List.of(USER_B, USER_C)),
                    eq("SHIFT_REQUEST_REMINDER_48H"),
                    anyString(), anyString(),
                    eq("SHIFT_SCHEDULE"), eq(SCHEDULE_ID),
                    eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                    anyString(), isNull());
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

            verify(notificationHelper, never()).notifyAll(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
                    .notifyAll(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

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

            verify(notificationHelper).notifyAll(
                    eq(List.of(USER_A, USER_B)),
                    eq("SHIFT_REQUEST_REMINDER"),
                    anyString(), anyString(),
                    eq("SHIFT_SCHEDULE"), eq(SCHEDULE_ID),
                    eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                    anyString(), isNull());
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

            verify(notificationHelper).notifyAll(
                    eq(List.of(USER_B, USER_C)),
                    eq("SHIFT_REQUEST_REMINDER_MANUAL"),
                    anyString(), anyString(),
                    eq("SHIFT_SCHEDULE"), eq(SCHEDULE_ID),
                    eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                    anyString(), isNull());
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
