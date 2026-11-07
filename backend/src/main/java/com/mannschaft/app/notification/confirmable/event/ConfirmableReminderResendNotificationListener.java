package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 確認通知の手動リマインド再送の配送リスナー（Issue #2997 / CMP-260827-1152 G4-b）。
 *
 * <p>{@code ConfirmableNotificationConfirmService#resendReminder} の業務TXが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火し、受信者ごとに
 * {@link NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）で配送する。
 * 1 人の失敗は内側 try で隔離し、他の受信者へ波及させない。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code NotificationHelper#notifyAll} を {@code resendReminder} のメソッドTXの内側で呼んでいた。
 * 通知は既定の {@code REQUIRED} で業務TXに参加するため、(1) 業務TXがロールバックしても通知が先に作られ、
 * (2) 通知側の DB 例外が rollback-only を残して 204 のはずの再送要求が 500 になりえた。</p>
 *
 * <h2>配送意味論（是正前と同一）</h2>
 * <p>通知種別 {@code CONFIRMABLE_NOTIFICATION_REMINDER}・優先度は確認通知の優先度・件名＝確認通知の件名・
 * 本文＝確認通知の本文（無ければ空）・ソース {@code CONFIRMABLE_NOTIFICATION}・実行者なし。
 * 可視性判定は {@code sendOne} の事前認可に任せる（deny は例外でなく {@code VISIBILITY_DENIED}）。</p>
 *
 * <h2>既知の限界</h2>
 * <p>HTTP 204 は「再送を受け付けた」の意味であり、配送完了を保証しない（M4）。
 * event-pool が飽和して投入が拒否された回の再送は失われる（業務TXはコミット済み。M9）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmableReminderResendNotificationListener {

    static final String NOTIFICATION_TYPE = "CONFIRMABLE_NOTIFICATION_REMINDER";
    static final String SOURCE_TYPE = "CONFIRMABLE_NOTIFICATION";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final ConfirmableNotificationRepository notificationRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "確認通知は棚卸し台帳で常時提供のコア機能であり、手動リマインドだけを止める停止条件が存在しない。"
                    + "管理者が明示的に実行した再送であり、落とすと未確認者に督促が届かずイベントは再生されない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReminderResend(ConfirmableReminderResendEvent event) {
        List<Long> recipients = event.recipientUserIds();
        if (recipients.isEmpty()) {
            return;
        }

        // 文面の材料はイベントではなく元の行から読み直す（業務TXは commit 済みで通知行は生存している）。
        ConfirmableNotificationEntity notification;
        try {
            notification = notificationRepository.findById(event.notificationId()).orElse(null);
        } catch (Exception e) {
            log.error("手動リマインド再送の元データ読み直しに失敗しました（配送を中止）: notificationId={}",
                    event.notificationId(), e);
            return;
        }
        if (notification == null) {
            log.error("手動リマインド再送の元データが見つかりません（配送を中止）: notificationId={}",
                    event.notificationId());
            return;
        }

        int delivered = 0;
        int denied = 0;
        int failed = 0;
        Long firstFailedUserId = null;
        for (Long recipientUserId : recipients) {
            try {
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(
                        buildRequest(notification, recipientUserId));
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    denied++;
                } else {
                    delivered++;
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = recipientUserId;
                }
                // 非同期イベント失敗の監査記録（規約上必須）。catch は TX 外なので rollback で消えない。
                log.error("手動リマインド再送の配送に失敗しました（この受信者はスキップ）: recipientUserId={}, notificationId={}",
                        recipientUserId, event.notificationId(), e);
            }
        }

        String summary = "手動リマインド再送の配送結果: notificationId={}, total={}, delivered={}, failed={}, denied={}, "
                + "firstFailedUserId={}";
        if (failed > 0) {
            log.error(summary, event.notificationId(), recipients.size(), delivered, failed, denied, firstFailedUserId);
        } else if (denied > 0) {
            log.warn(summary, event.notificationId(), recipients.size(), delivered, failed, denied, firstFailedUserId);
        } else {
            log.info("手動リマインド再送: notificationId={}, targetCount={}", event.notificationId(), recipients.size());
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(ConfirmableNotificationEntity notification, Long recipientUserId) {
        return new NotificationDeliveryRequest(
                recipientUserId,
                NOTIFICATION_TYPE,
                toNotificationPriority(notification.getPriority()),
                notification.getTitle(),
                notification.getBody() != null ? notification.getBody() : "",
                SOURCE_TYPE,
                notification.getId(),
                toNotificationScopeType(notification.getScopeType()),
                notification.getScopeId(),
                notification.getActionUrl(),
                null);
    }

    private static NotificationPriority toNotificationPriority(ConfirmableNotificationPriority priority) {
        return switch (priority) {
            case URGENT -> NotificationPriority.URGENT;
            case HIGH -> NotificationPriority.HIGH;
            case NORMAL -> NotificationPriority.NORMAL;
        };
    }

    private static NotificationScopeType toNotificationScopeType(ScopeType scopeType) {
        return switch (scopeType) {
            case TEAM -> NotificationScopeType.TEAM;
            case ORGANIZATION -> NotificationScopeType.ORGANIZATION;
            case PLATFORM -> NotificationScopeType.SYSTEM;
            case COMMITTEE -> NotificationScopeType.COMMITTEE;
        };
    }
}
