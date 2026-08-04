package com.mannschaft.app.notification.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.notification.repository.NotificationArchiveRepository;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.repository.NotificationSettingsRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    private final NotificationSettingsRepository notificationSettingsRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationArchiveRepository notificationArchiveRepository;
    /** MeterRegistry（optional。narrowed test context 等では不在・fan-out 系と同じ ObjectProvider 方式）。 */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** archive PII 即時消去の失敗を可観測化するメトリクス名。 */
    private static final String METRIC_ARCHIVE_DELETE_FAILED =
            "mannschaft.notification.anonymization.archive_delete_failed";

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

            notificationSettingsRepository.deleteByUserId(userId);
            log.debug("ユーザー退会: グローバル通知設定削除完了: userId={}", userId);

            // 第二陣E: 通知本体（title / body ＝個人の内容＝PII）を即時削除し、
            // V100.001 で撤廃する fk_notifications_user（CASCADE）を冗長化する。
            int deletedNotifications = notificationRepository.deleteByUserId(userId);
            log.debug("ユーザー退会: 通知本体削除完了: userId={}, deleted={}", userId, deletedNotifications);

            // 保持バッチ（Wave2-A）で notifications_archive へ移送済みの行にも title / body（PII）が
            // 残るため、即時消去層（UserAnonymizedEvent）で本体と同時に archive 側の PII も消す。
            // 30日後の AccountPurge 側には足さない（即時層の責務）。
            //
            // GDPR 即時消去の要である archive PII 削除は、外側の WARN 握り潰しに沈めない。
            // 失敗時は ERROR ログ＋メトリクスで可観測化し、PII 残留を検知可能にする
            // （握り潰し禁止・障害対応の原則2）。ただし例外は再送出し、他の削除同様に
            // 外側 catch へ伝播させて処理全体の失敗として扱う。
            try {
                int deletedArchive = notificationArchiveRepository.deleteByUserId(userId);
                log.debug("ユーザー退会: 通知アーカイブ削除完了: userId={}, deleted={}", userId, deletedArchive);
            } catch (Exception archiveEx) {
                log.error("ユーザー退会: 通知アーカイブPII即時消去に失敗（PII残留の恐れ）: userId={}, error={}",
                        userId, archiveEx.getMessage(), archiveEx);
                MeterRegistry registry =
                        meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
                if (registry != null) {
                    registry.counter(METRIC_ARCHIVE_DELETE_FAILED).increment();
                }
                throw archiveEx;
            }

            log.info("ユーザー退会: notificationドメイン匿名化完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: notificationドメイン匿名化失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
