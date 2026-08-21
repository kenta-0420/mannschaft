package com.mannschaft.app.event;

import com.mannschaft.app.event.event.EventAdvanceNoticeNotificationEvent;
import com.mannschaft.app.event.listener.EventAdvanceNoticeNotificationListener;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #2834 / CMP-056 型確立PR — {@link EventAdvanceNoticeNotificationListener} のユニットテスト。
 *
 * <h2>AC-7 の番人</h2>
 * <p>複数受信者（主催者 + 見守り者）がある経路で、{@link NotificationDeliveryRunner#sendOne} が
 * 受信者数ぶん<b>1件ずつ</b>呼ばれることを検証する（リスナー全体を1つの {@code REQUIRES_NEW} で
 * 包んでループしていないことの裏付け。1受信者の失敗が他受信者を巻き添えにしないことも同時に確認する）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventAdvanceNoticeNotificationListener ユニットテスト")
class EventAdvanceNoticeNotificationListenerTest {

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Test
    @DisplayName("AC-7: 受信者3名分のイベントで sendOne が3回・1件ずつ呼ばれる")
    void 受信者数ぶんsendOneが1件ずつ呼ばれる() {
        EventAdvanceNoticeNotificationListener listener =
                new EventAdvanceNoticeNotificationListener(notificationDeliveryRunner);

        NotificationDeliveryRequest r1 = buildRequest(1L);
        NotificationDeliveryRequest r2 = buildRequest(2L);
        NotificationDeliveryRequest r3 = buildRequest(3L);

        given(notificationDeliveryRunner.sendOne(any())).willReturn(
                NotificationEntity.builder().userId(1L).build());

        listener.onEventAdvanceNoticeNotification(
                new EventAdvanceNoticeNotificationEvent(List.of(r1, r2, r3)));

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
        verify(notificationDeliveryRunner).sendOne(r1);
        verify(notificationDeliveryRunner).sendOne(r2);
        verify(notificationDeliveryRunner).sendOne(r3);
    }

    @Test
    @DisplayName("1受信者のRunner例外が他の受信者への配送を止めない")
    void 一人の例外が他の受信者への配送を止めない() {
        EventAdvanceNoticeNotificationListener listener =
                new EventAdvanceNoticeNotificationListener(notificationDeliveryRunner);

        NotificationDeliveryRequest broken = buildRequest(1L);
        NotificationDeliveryRequest ok = buildRequest(2L);

        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner).sendOne(broken);
        given(notificationDeliveryRunner.sendOne(ok)).willReturn(NotificationEntity.builder().userId(2L).build());

        listener.onEventAdvanceNoticeNotification(
                new EventAdvanceNoticeNotificationEvent(List.of(broken, ok)));

        // 例外を投げた1件目のあとも、2件目の sendOne 呼び出しに到達していること。
        verify(notificationDeliveryRunner).sendOne(broken);
        verify(notificationDeliveryRunner).sendOne(ok);
    }

    private NotificationDeliveryRequest buildRequest(Long recipientUserId) {
        return new NotificationDeliveryRequest(
                recipientUserId,
                "EVENT_LATE_ARRIVAL_NOTICE",
                NotificationPriority.NORMAL,
                "件名", "本文",
                "EVENT", 1L,
                NotificationScopeType.TEAM, 10L,
                "/teams/10/events/1",
                99L);
    }
}
