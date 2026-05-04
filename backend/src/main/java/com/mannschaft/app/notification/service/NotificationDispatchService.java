package com.mannschaft.app.notification.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.NotificationSourceTypeMapper;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 通知配信サービス。通知の実際の送信処理（WebSocket・PWA Push等）を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final PushSubscriptionService pushSubscriptionService;
    private final NotificationPreferenceService preferenceService;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationMapper notificationMapper;

    /**
     * F00 Phase F セキュリティ漏れ修正で導入。配信直前の二重防御として
     * 受信者がソースコンテンツを閲覧可能かを再確認する。
     * {@link NotificationService} 側で既にガード済だが、外部から
     * 直接 {@link #dispatch} を呼ぶ経路 (DB から復元した古い通知の再送等) でも
     * 漏れなくガードするための fail-safe。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §11.1 / §13.5。
     */
    private final ContentVisibilityChecker visibilityChecker;

    /**
     * 通知を配信する。ユーザーの設定を確認し、有効なチャネルに送信する。
     * 確認通知（CONFIRMABLE_NOTIFICATION*）は opt-out 設定を無視して強制配信する。
     *
     * <p>F00 Phase F: 配信直前にも visibility ガードを行い、Resolver 配備済の
     * sourceType に対しては受信者の閲覧可否を確認してから送信する。
     */
    @Async
    public void dispatch(NotificationEntity notification) {
        if (notification == null) {
            return;
        }
        Long userId = notification.getUserId();

        // ----------------------------------------------------------------
        // F00 Phase F: 配信前 visibility ガード (二重防御 §11.1)
        // ----------------------------------------------------------------
        if (!isAccessibleForRecipient(notification)) {
            log.warn("通知配信スキップ (visibility deny): userId={}, type={}, sourceType={}, sourceId={}",
                    userId, notification.getNotificationType(),
                    notification.getSourceType(), notification.getSourceId());
            return;
        }

        // 確認通知（CONFIRMABLE_NOTIFICATION*）は強制配信のため opt-out チェックをスキップ
        if (isConfirmableNotification(notification.getNotificationType())) {
            log.debug("確認通知は強制配信（opt-out スキップ）: userId={}, type={}",
                    userId, notification.getNotificationType());
            sendViaWebSocket(notification);
            sendViaPush(notification);
            return;
        }

        // スコープ別の通知設定を確認
        boolean scopeEnabled = preferenceService.isNotificationEnabled(
                userId, notification.getScopeType().name(), notification.getScopeId());
        if (!scopeEnabled) {
            log.debug("通知スキップ(スコープ無効): userId={}, scopeType={}, scopeId={}",
                    userId, notification.getScopeType(), notification.getScopeId());
            return;
        }

        // 通知種別の設定を確認
        boolean typeEnabled = preferenceService.isTypeEnabled(userId, notification.getNotificationType());
        if (!typeEnabled) {
            log.debug("通知スキップ(種別無効): userId={}, type={}", userId, notification.getNotificationType());
            return;
        }

        // WebSocket送信
        sendViaWebSocket(notification);

        // PWA Push送信
        sendViaPush(notification);
    }

    /**
     * 配信対象通知に対する受信者の閲覧可否を判定する (F00 Phase F)。
     *
     * <p>fail-soft: {@code sourceType} が {@link ReferenceType} に解決できない、
     * または {@code sourceId} が null の通知は判定対象外として true を返す。
     *
     * @param notification 配信対象通知
     * @return アクセス可能または判定対象外なら true
     */
    private boolean isAccessibleForRecipient(NotificationEntity notification) {
        Long sourceId = notification.getSourceId();
        if (sourceId == null) {
            return true;
        }
        Optional<ReferenceType> refType =
                NotificationSourceTypeMapper.resolve(notification.getSourceType());
        if (refType.isEmpty()) {
            return true;
        }
        return visibilityChecker.canView(refType.get(), sourceId, notification.getUserId());
    }

    /**
     * 確認通知種別かどうかを判定する（opt-out 無視の強制配信対象）。
     * CONFIRMABLE_NOTIFICATION / CONFIRMABLE_NOTIFICATION_REMINDER_1 / CONFIRMABLE_NOTIFICATION_REMINDER_2
     * のすべてに対応するよう前方一致で判定する。
     */
    private boolean isConfirmableNotification(String notificationType) {
        return notificationType != null && notificationType.startsWith("CONFIRMABLE_NOTIFICATION");
    }

    /**
     * WebSocket (STOMP) 経由でリアルタイム通知を送信する。
     * クライアントは /user/{userId}/queue/notifications を購読する。
     */
    private void sendViaWebSocket(NotificationEntity notification) {
        try {
            NotificationResponse response = notificationMapper.toNotificationResponse(notification);
            messagingTemplate.convertAndSendToUser(
                    notification.getUserId().toString(),
                    "/queue/notifications",
                    response);
            log.debug("WebSocket通知送信: userId={}, notificationId={}",
                    notification.getUserId(), notification.getId());
        } catch (Exception e) {
            log.warn("WebSocket通知送信失敗: userId={}, error={}",
                    notification.getUserId(), e.getMessage());
        }
    }

    /**
     * PWA Push (Web Push API) 経由で通知を送信する。
     * VAPID署名によるHTTP Pushプロトコルを使用する。
     * 本番環境ではweb-push-javaライブラリを統合して実装する。
     */
    private void sendViaPush(NotificationEntity notification) {
        List<PushSubscriptionEntity> subscriptions =
                pushSubscriptionService.listSubscriptions(notification.getUserId());

        if (subscriptions.isEmpty()) {
            log.debug("プッシュ購読なし: userId={}", notification.getUserId());
            return;
        }

        for (PushSubscriptionEntity subscription : subscriptions) {
            try {
                // NOTE: 本番環境ではVAPIDライブラリ（web-push-java）で実際のHTTP Push送信を行う
                // 現在はログ記録のみ。ライブラリ統合時に以下を実装:
                // 1. VAPID鍵ペアで署名
                // 2. subscription.getEndpoint() に暗号化ペイロードをPOST
                // 3. レスポンスコード410/404の場合は購読を自動削除
                subscription.updateLastUsedAt();
                log.info("WebPush送信: userId={}, endpoint={}...{}",
                        notification.getUserId(),
                        subscription.getEndpoint().substring(0, Math.min(50, subscription.getEndpoint().length())),
                        subscription.getEndpoint().length() > 50 ? "..." : "");
            } catch (Exception e) {
                log.warn("WebPush送信失敗: userId={}, endpoint={}, error={}",
                        notification.getUserId(), subscription.getEndpoint(), e.getMessage());
            }
        }
    }
}
