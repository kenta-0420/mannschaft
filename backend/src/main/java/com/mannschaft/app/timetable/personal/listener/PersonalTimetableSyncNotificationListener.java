package com.mannschaft.app.timetable.personal.listener;

import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.timetable.personal.event.PersonalTimetableSyncNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F03.15 Phase 4 個人時間割リンク反映の通知配送リスナー（Issue #2834 / CMP-056 型確立PR・二段構え第2段）。
 *
 * <p>{@code PersonalTimetableLinkSyncListener}（第1段）の反映トランザクションが commit された後に
 * 非同期（{@code event-pool}）で発火する。受信者ごとに
 * {@link NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）を<b>1件ずつ</b>呼ぶ
 * （AC-7: リンク数が多い場合でも、リスナー全体を1つの {@code REQUIRES_NEW} で包んでループしない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalTimetableSyncNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPersonalTimetableSyncNotification(PersonalTimetableSyncNotificationEvent event) {
        for (NotificationDeliveryRequest request : event.requests()) {
            sendOne(request);
        }
    }

    private void sendOne(NotificationDeliveryRequest request) {
        try {
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                log.warn("個人時間割リンク反映通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
            }
        } catch (Exception e) {
            log.error("個人時間割リンク反映通知の配送に失敗しました: "
                            + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                    request.recipientUserId(), request.notificationType(),
                    request.sourceType(), request.sourceId(), e);
        }
    }
}
