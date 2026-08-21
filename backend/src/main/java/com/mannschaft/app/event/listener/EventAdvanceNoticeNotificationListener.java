package com.mannschaft.app.event.listener;

import com.mannschaft.app.event.event.EventAdvanceNoticeNotificationEvent;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F03.12 事前遅刻・欠席連絡の通知配送リスナー（Issue #2834 / CMP-056 型確立PR）。
 *
 * <p>{@code EventRsvpService} の業務トランザクションが commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。受信者（主催者 + 見守り者）ごとに
 * {@link NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）を<b>1件ずつ</b>呼ぶ
 * （AC-7: リスナー全体を1つの {@code REQUIRES_NEW} で包んでループしない。1受信者の失敗が
 * 他受信者を巻き添えにしないため）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAdvanceNoticeNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventAdvanceNoticeNotification(EventAdvanceNoticeNotificationEvent event) {
        for (NotificationDeliveryRequest request : event.requests()) {
            sendOne(request);
        }
    }

    private void sendOne(NotificationDeliveryRequest request) {
        try {
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
                log.warn("事前連絡通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
            }
        } catch (Exception e) {
            log.error("事前連絡通知の配送に失敗しました: "
                            + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}, actorId={}",
                    request.recipientUserId(), request.notificationType(),
                    request.sourceType(), request.sourceId(), request.actorId(), e);
        }
    }
}
