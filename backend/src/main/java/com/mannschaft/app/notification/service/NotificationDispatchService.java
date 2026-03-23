package com.mannschaft.app.notification.service;

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
     * 通知を配信する。ユーザーの設定を確認し、有効なチャネルに送信する。
     */
    @Async
    public void dispatch(NotificationEntity notification) {
        Long userId = notification.getUserId();

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
