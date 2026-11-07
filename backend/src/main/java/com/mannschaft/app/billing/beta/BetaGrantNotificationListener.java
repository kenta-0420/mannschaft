package com.mannschaft.app.billing.beta;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 業務コミット後の同期入口で、別Beanへの非同期投入拒否を観測する。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BetaGrantNotificationListener {

    private final BetaGrantNotificationSender sender;
    private final NotificationDeliveryRunner notificationDeliveryRunner;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "ベータ特典の本人通知は確定した権利の付与・取消を知らせる。イベントは再送されず、閉栓で落とすと本人が権利変更を認識できないため、既存の通知動作を常に維持する")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBetaGrantNotification(BetaGrantNotificationEvent event) {
        try {
            sender.send(event, () -> deliver(event));
        } catch (RejectedExecutionException ex) {
            log.warn("ベータ特典通知の非同期投入に失敗（本体は確定済み）type={}, userId={}",
                    event.type(), event.recipientUserId(), ex);
        }
    }

    /** 確定後に作ったcallbackから本人1件を既存REQUIRES_NEWへ渡す。 */
    private void deliver(BetaGrantNotificationEvent event) {
        notificationDeliveryRunner.sendOne(new NotificationDeliveryRequest(
                event.recipientUserId(), event.type().name(), event.type().getPriority(),
                event.title(), event.body(), event.type().getSourceType(), null,
                NotificationScopeType.PERSONAL, event.recipientUserId(), null, null));
    }
}
