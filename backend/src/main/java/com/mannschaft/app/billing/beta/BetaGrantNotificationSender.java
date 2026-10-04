package com.mannschaft.app.billing.beta;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 既存event-poolで本人通知を配送し、通知側の失敗を業務TXから分離する。 */
@Slf4j
@Component
public class BetaGrantNotificationSender {

    @Async("event-pool")
    public void send(BetaGrantNotificationEvent event, Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException ex) {
            log.warn("ベータ特典通知の配送に失敗（本体は確定済み）type={}, userId={}",
                    event.type(), event.recipientUserId(), ex);
        }
    }
}
