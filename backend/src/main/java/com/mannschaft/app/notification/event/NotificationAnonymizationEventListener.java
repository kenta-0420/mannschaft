package com.mannschaft.app.notification.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
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
 *   <li>通知本体削除（クロスドメインFK撤廃キャンペーン 第二陣E で追加）</li>
 * </ul>
 * </p>
 *
 * <p><b>通知本体（notifications）の即時削除（第二陣E）:</b>
 * V100.001 で {@code fk_notifications_user}（user_id → users ON DELETE CASCADE・クロスドメインFK）を
 * 撤廃するにあたり、退会フローで本リスナーが先行削除することで CASCADE を冗長化する。
 * title / body は宛先ユーザー向けに作られた個人の内容（PII）で再設定復旧の性質でもないため、
 * §13.12 二層削除モデルの「即時消去」対象として {@link UserAnonymizedEvent}（退会受付直後）で削除する。
 * 新規リスナーは作らず、本既存リスナー（preferences / push を既に削除）に集約する。
 * なお {@code fk_notifications_actor}（actor_id → users SET NULL）は user CASCADE ではないため対象外。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationAnonymizationEventListener {

    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final NotificationTypePreferenceRepository notificationTypePreferenceRepository;
    private final NotificationRepository notificationRepository;

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

            // 第二陣E: 通知本体（title / body ＝個人の内容＝PII）を即時削除し、
            // V100.001 で撤廃する fk_notifications_user（CASCADE）を冗長化する。
            int deletedNotifications = notificationRepository.deleteByUserId(userId);
            log.debug("ユーザー退会: 通知本体削除完了: userId={}, deleted={}", userId, deletedNotifications);

            log.info("ユーザー退会: notificationドメイン匿名化完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: notificationドメイン匿名化失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
