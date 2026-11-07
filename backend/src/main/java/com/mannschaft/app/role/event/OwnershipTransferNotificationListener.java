package com.mannschaft.app.role.event;

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
 * オーナー委譲オファーの付随通知の配送リスナー（Issue #2997 / CMP-260827-1152 G5）。
 *
 * <p>業務TXは受信者と文面を確定して {@link OwnershipTransferNotificationEvent} を publish するだけに留め、
 * 本リスナーが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取り
 * {@link NotificationDeliveryRunner#sendOne}（1 件 = 1 独立トランザクション）へ委譲する。
 * 通知の失敗は打診・辞退の確定へ波及しない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OwnershipTransferNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "オーナー委譲の打診到達・辞退通知は対応する gate_key が無く停止条件を宣言できない。"
                    + "落とすと打診相手・発行者がオファーの存在や結果に気づけず、イベントは再生されず取りこぼしは復旧不能であるため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOwnershipTransferNotification(OwnershipTransferNotificationEvent event) {
        if (event.recipientUserId() == null) {
            return;
        }
        try {
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(new NotificationDeliveryRequest(
                    event.recipientUserId(),
                    event.notificationType(),
                    event.priority(),
                    event.title(),
                    event.body(),
                    "USER",
                    null,
                    event.scopeType(),
                    event.scopeId(),
                    event.actionUrl(),
                    event.actorId()));
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                log.warn("オーナー委譲通知が visibility deny によりスキップされました: type={}, recipientUserId={}",
                        event.notificationType(), event.recipientUserId());
            }
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録。catch は業務TX外なので rollback で消えない。
            log.error("オーナー委譲通知の配送に失敗しました: type={}, recipientUserId={}",
                    event.notificationType(), event.recipientUserId(), e);
        }
    }
}
