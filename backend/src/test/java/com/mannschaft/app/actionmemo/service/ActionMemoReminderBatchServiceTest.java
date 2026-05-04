package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ActionMemoReminderBatchService} 単体テスト（F02.5 Phase 6-2）。
 *
 * <p>{@code executeAt(LocalTime)} を直接呼び出すことで、
 * {@code LocalTime.now()} のモックを不要にしてテスト可能にしている。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoReminderBatchService 単体テスト")
class ActionMemoReminderBatchServiceTest {

    @Mock
    private UserActionMemoSettingsRepository settingsRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ActionMemoReminderBatchService service;

    @Test
    @DisplayName("execute_対象なし_何もしない")
    void execute_対象なし_何もしない() {
        // given
        given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                .willReturn(List.of());

        // when
        service.executeAt(LocalTime.of(9, 0));

        // then
        verify(notificationService, never()).createNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditLogService, never()).record(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("execute_時刻一致_通知が送られる")
    void execute_時刻一致_通知が送られる() {
        // given
        LocalTime targetTime = LocalTime.of(9, 0);
        LocalDate today = LocalDate.of(2026, 5, 4);
        UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                .userId(1L)
                .reminderEnabled(true)
                .reminderTime(targetTime)
                .build();

        given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                .willReturn(List.of(settings));

        // when
        service.executeAt(targetTime, today);

        // then: actionUrl が /action-memo?date=YYYY-MM-DD 形式になっていることを ArgumentCaptor で検証
        verify(notificationService, times(1)).createNotification(
                eq(1L),
                eq("ACTION_MEMO_REMINDER"),
                eq(NotificationPriority.NORMAL),
                eq("行動メモのリマインド"),
                eq("今日の行動メモを記録しましょう"),
                eq("ACTION_MEMO"),
                eq(null),
                eq(NotificationScopeType.PERSONAL),
                eq(1L),
                contains("/action-memo?date="),
                eq(null)
        );
        verify(auditLogService, times(1)).record(
                "ACTION_MEMO_REMINDER_BATCH", null, null, null, null, null, null, null,
                "{\"targets\":1,\"notified\":1}");
    }

    @Test
    @DisplayName("execute_時刻不一致_通知が送られない")
    void execute_時刻不一致_通知が送られない() {
        // given
        LocalTime reminderTime = LocalTime.of(9, 0);
        LocalTime nowTime = LocalTime.of(10, 0);

        UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                .userId(2L)
                .reminderEnabled(true)
                .reminderTime(reminderTime)
                .build();

        given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                .willReturn(List.of(settings));

        // when
        service.executeAt(nowTime);

        // then
        verify(notificationService, never()).createNotification(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditLogService, never()).record(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
