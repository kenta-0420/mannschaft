package com.mannschaft.app.timetable.personal;



import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.timetable.personal.event.PersonalTimetableSyncNotificationEvent;
import com.mannschaft.app.timetable.personal.listener.PersonalTimetableSyncNotificationListener;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #2834 / CMP-056 型確立PR — {@link PersonalTimetableSyncNotificationListener} のユニットテスト
 * （二段構え第2段）。
 *
 * <h2>AC-7 の番人</h2>
 * <p>リンク数が多い場合でも、{@link NotificationDeliveryRunner#sendOne} が受信者数ぶん
 * <b>1件ずつ</b>呼ばれることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableSyncNotificationListener ユニットテスト")
class PersonalTimetableSyncNotificationListenerTest {

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Test
    @DisplayName("AC-7: 複数コマ分の通知要求で sendOne が1件ずつ呼ばれる")
    void 複数コマ分の通知要求でsendOneが1件ずつ呼ばれる() {
        PersonalTimetableSyncNotificationListener listener =
                new PersonalTimetableSyncNotificationListener(notificationDeliveryRunner);

        NotificationDeliveryRequest r1 = buildRequest(1L);
        NotificationDeliveryRequest r2 = buildRequest(2L);

        given(notificationDeliveryRunner.sendOne(any())).willReturn(
                NotificationDeliveryResult.DELIVERED);

        listener.onPersonalTimetableSyncNotification(
                new PersonalTimetableSyncNotificationEvent(List.of(r1, r2)));

        verify(notificationDeliveryRunner, times(2)).sendOne(any());
        verify(notificationDeliveryRunner).sendOne(r1);
        verify(notificationDeliveryRunner).sendOne(r2);
    }

    private NotificationDeliveryRequest buildRequest(Long recipientUserId) {
        return new NotificationDeliveryRequest(
                recipientUserId,
                "TIMETABLE_CHANGE_SYNCED",
                NotificationPriority.NORMAL,
                "件名", "本文",
                "SCHEDULE", 1L,
                NotificationScopeType.PERSONAL, recipientUserId,
                "/schedules/1",
                null);
    }
}
