package com.mannschaft.app.schedule;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.schedule.event.ReminderNotificationEvent;
import com.mannschaft.app.schedule.service.ScheduleReminderNotificationListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleReminderNotificationListener} の単体テスト（機能55 第二陣）。
 * リマインダー通知イベントを受けて NotificationHelper.notifyAll（IN_APP + PUSH）が呼ばれることを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleReminderNotificationListener 単体テスト")
class ScheduleReminderNotificationListenerTest {

    @Mock
    private NotificationHelper notificationHelper;

    @InjectMocks
    private ScheduleReminderNotificationListener listener;

    @Test
    @DisplayName("対象者あり_notifyAllでIN_APP+PUSH配信される")
    void 対象者あり_notifyAllで配信される() {
        // given
        ReminderNotificationEvent event = new ReminderNotificationEvent(
                1L, NotificationScopeType.TEAM, 50L, List.of(100L, 200L),
                "まもなく予定の時刻です", "まもなく予定が始まります: テスト予定", "/schedules/1");

        // when
        listener.onReminderNotification(event);

        // then
        verify(notificationHelper).notifyAll(
                eq(List.of(100L, 200L)),
                eq("SCHEDULE_REMINDER"),
                eq(NotificationPriority.NORMAL),
                eq("まもなく予定の時刻です"),
                eq("まもなく予定が始まります: テスト予定"),
                eq("SCHEDULE"),
                eq(1L),
                eq(NotificationScopeType.TEAM),
                eq(50L),
                eq("/schedules/1"),
                isNull());
    }

    @Test
    @DisplayName("対象者なし_notifyAll呼ばれない")
    void 対象者なし_notifyAll呼ばれない() {
        // given
        ReminderNotificationEvent event = new ReminderNotificationEvent(
                1L, NotificationScopeType.TEAM, 50L, List.of(),
                "title", "body", "/schedules/1");

        // when
        listener.onReminderNotification(event);

        // then
        verify(notificationHelper, never()).notifyAll(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(NotificationPriority.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(NotificationScopeType.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
