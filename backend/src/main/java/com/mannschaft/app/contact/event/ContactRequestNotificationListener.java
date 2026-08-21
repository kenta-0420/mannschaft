package com.mannschaft.app.contact.event;

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
 * 連絡先申請の通知配送リスナー（Issue #2834 / CMP-056 型確立PR）。
 *
 * <p>{@code ContactRequestService} の業務トランザクションが commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。実際の生成・配信は
 * {@link NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）へ委譲し、通知の DB 障害が
 * 業務処理（申請の INSERT / 承認更新）を巻き戻さないようにする。</p>
 *
 * <h2>境界: 例外・deny のログはこの非TXリスナーで書く</h2>
 * <p>{@code runner.sendOne} 呼び出しを {@code try/catch} で囲むのは本リスナー（トランザクション境界の
 * <b>外</b>）であり、Runner 内部（{@code REQUIRES_NEW} トランザクションの<b>内側</b>）で catch しない。
 * ロールバックオンリーのトランザクション内で監査ログを書いても一緒に巻き戻るため（設計確定 issue
 * コメント参照）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactRequestNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContactRequestNotification(ContactRequestNotificationEvent event) {
        NotificationDeliveryRequest request = event.request();
        try {
            NotificationEntity created = notificationDeliveryRunner.sendOne(request);
            if (created == null) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                log.warn("連絡先申請通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
            }
        } catch (Exception e) {
            log.error("連絡先申請通知の配送に失敗しました: "
                            + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}, actorId={}",
                    request.recipientUserId(), request.notificationType(),
                    request.sourceType(), request.sourceId(), request.actorId(), e);
        }
    }
}
