package com.mannschaft.app.billing.beta;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 既存event-poolで本人通知を配送し、通知側の失敗を業務TXから分離する。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BetaGrantNotificationSender {

    private final NotificationDeliveryRunner notificationDeliveryRunner;

    @Async("event-pool")
    public void send(BetaGrantNotificationEvent event) {
        try {
            notificationDeliveryRunner.sendOne(new NotificationDeliveryRequest(
                    event.recipientUserId(), event.type().name(), event.type().getPriority(),
                    event.title(), event.body(), event.type().getSourceType(), null,
                    NotificationScopeType.PERSONAL, event.recipientUserId(), null, null));
        } catch (RuntimeException ex) {
            log.warn("ベータ特典通知の配送に失敗（本体は確定済み）type={}, userId={}",
                    event.type(), event.recipientUserId(), ex);
        }
    }
}
