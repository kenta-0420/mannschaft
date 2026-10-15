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
 * リマインダー通知イベントを受けて NotificationHelper.notifyAllPreAuthorized（IN_APP + PUSH）が
 * 呼ばれることを検証する（配信＝受信権 統一・(B) レグ取りこぼし番人）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleReminderNotificationListener 単体テスト")
class ScheduleReminderNotificationListenerTest {

    @Mock
    private NotificationHelper notificationHelper;

    @InjectMocks
    private ScheduleReminderNotificationListener listener;

    @Test
    @DisplayName("対象者あり_notifyAllPreAuthorizedでIN_APP+PUSH配信される（(B)レグ番人）")
    void 対象者あり_notifyAllPreAuthorizedで配信される() {
        // given: 受信者は schedule_attendances 行（配信母集団 materialize）由来で事前認可済み
        ReminderNotificationEvent event = new ReminderNotificationEvent(
                1L, NotificationScopeType.TEAM, 50L, List.of(100L, 200L),
                "まもなく予定の時刻です", "まもなく予定が始まります: テスト予定", "/schedules/1");

        // when
        listener.onReminderNotification(event);

        // then: canView 二重判定を通さない notifyAllPreAuthorized 経由で配信される
        verify(notificationHelper).notifyAllPreAuthorized(
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
        // 取りこぼし非回帰: canView ゲート付き notifyAll は使わない
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

    @Test
    @DisplayName("対象者なし_notifyAllPreAuthorized呼ばれない")
    void 対象者なし_notifyAllPreAuthorized呼ばれない() {
        // given
        ReminderNotificationEvent event = new ReminderNotificationEvent(
                1L, NotificationScopeType.TEAM, 50L, List.of(),
                "title", "body", "/schedules/1");

        // when
        listener.onReminderNotification(event);

        // then
        verify(notificationHelper, never()).notifyAllPreAuthorized(
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
