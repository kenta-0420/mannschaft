package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 確認通知リマインドの通知配送リスナー（CMP-260925-0905）。
 *
 * <p>送信済み記録のトランザクションが commit された後（{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火し、
 * 1件ずつ {@link NotificationDeliveryRunner#sendOne} へ委譲する（1件の失敗が他の受信者を巻き添えにしない）。
 * 失敗は握りつぶさず ERROR ログに残す。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmableReminderNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "確認通知リマインドは対応する gate_key が無く停止条件を宣言できないため常時実行する（リマインドバッチと同じ扱い）")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReminderNotification(ConfirmableReminderNotificationEvent event) {
        NotificationDeliveryRequest request = event.request();
        try {
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                log.warn("確認通知リマインドが visibility deny によりスキップされました: "
                                + "notificationId={}, recipientUserId={}, notificationType={}",
                        event.notificationId(), request.recipientUserId(), request.notificationType());
            }
        } catch (Exception e) {
            log.error("確認通知リマインドの配送に失敗しました: notificationId={}, recipientUserId={}, notificationType={}",
                    event.notificationId(), request.recipientUserId(), request.notificationType(), e);
        }
    }
}
