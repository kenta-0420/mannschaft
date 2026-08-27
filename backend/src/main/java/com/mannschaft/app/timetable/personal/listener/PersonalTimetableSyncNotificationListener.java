package com.mannschaft.app.timetable.personal.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
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

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。個人時間割の同期結果の通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPersonalTimetableSyncNotification(PersonalTimetableSyncNotificationEvent event) {
        for (NotificationDeliveryRequest request : event.requests()) {
            sendOne(request);
        }
    }

    private void sendOne(NotificationDeliveryRequest request) {
        try {
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
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
