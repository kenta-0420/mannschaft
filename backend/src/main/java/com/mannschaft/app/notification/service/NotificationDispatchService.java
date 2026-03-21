package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知配信サービス。通知の実際の送信処理（WebSocket・PWA Push等）を担当する。
 *
 * <p>現時点では送信処理はTODOとし、各チャネルへの送信ロジックは今後実装する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final PushSubscriptionService pushSubscriptionService;
    private final NotificationPreferenceService preferenceService;

    /**
     * 通知を配信する。ユーザーの設定を確認し、有効なチャネルに送信する。
     *
     * @param notification 通知エンティティ
     */
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
     * WebSocket経由で通知を送信する。
     *
     * @param notification 通知エンティティ
     */
    private void sendViaWebSocket(NotificationEntity notification) {
        // TODO: WebSocket (STOMP) 経由でリアルタイム通知を送信
        log.debug("WebSocket通知送信(TODO): userId={}, notificationId={}",
                notification.getUserId(), notification.getId());
    }

    /**
     * PWA Push経由で通知を送信する。
     *
     * @param notification 通知エンティティ
     */
    private void sendViaPush(NotificationEntity notification) {
        List<PushSubscriptionEntity> subscriptions =
                pushSubscriptionService.listSubscriptions(notification.getUserId());

        if (subscriptions.isEmpty()) {
            log.debug("プッシュ購読なし: userId={}", notification.getUserId());
            return;
        }

        for (PushSubscriptionEntity subscription : subscriptions) {
            // TODO: Web Push API (VAPID) を使用してプッシュ通知を送信
            log.debug("PWA Push送信(TODO): userId={}, endpoint={}",
                    notification.getUserId(), subscription.getEndpoint());
        }
    }
}
