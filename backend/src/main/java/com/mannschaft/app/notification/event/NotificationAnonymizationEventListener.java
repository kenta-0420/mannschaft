package com.mannschaft.app.notification.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 退会匿名化イベントに応答して notification ドメインの関連データを削除するリスナー。
 *
 * <p>処理内容:
 * <ul>
 *   <li>プッシュ通知サブスクリプション削除</li>
 *   <li>通知設定削除</li>
 *   <li>通知種別設定削除</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationAnonymizationEventListener {

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final NotificationTypePreferenceRepository notificationTypePreferenceRepository;

    /**
     * ユーザー退会匿名化イベントを受け取り、notification ドメインの関連データを削除する。
     *
     * @param event 退会匿名化イベント
     */
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            pushSubscriptionRepository.deleteByUserId(userId);
            log.debug("ユーザー退会: プッシュ購読削除完了: userId={}", userId);

            notificationPreferenceRepository.deleteByUserId(userId);
            log.debug("ユーザー退会: 通知設定削除完了: userId={}", userId);

            notificationTypePreferenceRepository.deleteByUserId(userId);
            log.debug("ユーザー退会: 通知種別設定削除完了: userId={}", userId);

            log.info("ユーザー退会: notificationドメイン匿名化完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: notificationドメイン匿名化失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
