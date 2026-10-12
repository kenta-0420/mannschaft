package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.event.ConfirmableReminderNotificationEvent;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationSettingsRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 確認通知リマインドの「受信者1人ぶん」「通知1件ぶんのアラート」を独立トランザクション
 * （{@link Propagation#REQUIRES_NEW}）で実行する Bean（CMP-260925-0905）。
 *
 * <h2>是正前の欠陥</h2>
 * <ul>
 *   <li>{@code ConfirmableNotificationReminderBatchService#runBatch} が同一クラスの {@code @Transactional}
 *       メソッドを自己呼び出ししており、トランザクションが張られず {@code recipient.user} の遅延読み込みが失敗した</li>
 *   <li>通知1件を1トランザクションにまとめると、1人の DB 例外が rollback-only を残して他の受信者の記録まで巻き戻る</li>
 *   <li>トランザクション内で通知を作ると、commit 前に WebSocket / Push が出うる</li>
 * </ul>
 *
 * <p>本 Bean は送信済み記録の確定と通知配送要求イベントの publish だけを行う。実通知は commit 後
 * （{@code AFTER_COMMIT}）に {@code ConfirmableReminderNotificationListener} が1件ずつ配送する。
 * 例外は握りつぶさず呼び出し元（非トランザクションの {@code runBatch}）へ伝播し、そこでログに残る。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmableNotificationReminderRunner {

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final ConfirmableNotificationSettingsRepository settingsRepository;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 受信者1人ぶんのリマインドを確定し、通知配送要求を publish する。
     *
     * @param recipientId 受信者行ID
     * @param now         判定基準時刻
     * @return リマインドを確定した場合 1、対象外なら 0
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int remindRecipient(Long recipientId, LocalDateTime now) {
        // 抽出後に確認・除外・通知の取消が起きている可能性があるため読み直して再判定する（冪等性）。
        ConfirmableNotificationRecipientEntity recipient = recipientRepository.findById(recipientId).orElse(null);
        if (recipient == null || Boolean.TRUE.equals(recipient.getIsConfirmed()) || recipient.getExcludedAt() != null) {
            return 0;
        }
        ConfirmableNotificationEntity notification = recipient.getConfirmableNotification();
        if (notification.getStatus() != ConfirmableNotificationStatus.ACTIVE) {
            return 0;
        }

        String notificationType;
        if (recipient.needsFirstReminder(now)) {
            recipient.markFirstReminderSent();
            notificationType = "CONFIRMABLE_NOTIFICATION_REMINDER_1";
        } else if (recipient.needsSecondReminder(now)) {
            recipient.markSecondReminderSent();
            notificationType = "CONFIRMABLE_NOTIFICATION_REMINDER_2";
        } else {
            return 0;
        }
        recipientRepository.save(recipient);

        eventPublisher.publishEvent(new ConfirmableReminderNotificationEvent(
                new NotificationDeliveryRequest(
                        recipient.getUser().getId(),
                        notificationType,
                        toNotificationPriority(notification),
                        notification.getTitle(),
                        notification.getBody() != null ? notification.getBody() : "",
                        "CONFIRMABLE_NOTIFICATION",
                        notification.getId(),
                        toNotificationScopeType(notification),
                        notification.getScopeId(),
                        notification.getActionUrl(),
                        null),
                notification.getId()));
        log.debug("リマインドを確定: notificationId={}, userId={}, type={}",
                notification.getId(), recipient.getUser().getId(), notificationType);
        return 1;
    }

    /**
     * 確認率が閾値を下回った場合に、送信者アラートの通知配送要求を publish する。
     *
     * <p>確認率 = 確認済み件数 / 除外を除いた受信者数 × 100</p>
     *
     * @param notificationId 確認通知ID
     * @return アラートを publish した場合 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean alertIfNeeded(Long notificationId) {
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null || notification.getStatus() != ConfirmableNotificationStatus.ACTIVE
                || notification.getCreatedBy() == null) {
            return false;
        }

        int alertThreshold = settingsRepository
                .findByScopeTypeAndScopeId(notification.getScopeType(), notification.getScopeId())
                .map(ConfirmableNotificationSettingsEntity::getSenderAlertThresholdPercent)
                .orElse(80);

        long confirmedCount = recipientRepository
                .countByConfirmableNotificationIdAndIsConfirmedTrue(notification.getId());
        long totalActiveCount = recipientRepository
                .countByConfirmableNotificationIdAndExcludedAtIsNull(notification.getId());
        if (totalActiveCount == 0) {
            return false;
        }
        int confirmRate = (int) (confirmedCount * 100L / totalActiveCount);
        if (confirmRate >= alertThreshold) {
            return false;
        }

        Long senderId = notification.getCreatedBy().getId();
        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(senderId));
        String title = messageSource.getMessage(
                "notification.confirmable.senderAlert.title", null, "確認通知のアラート", locale);
        String body = messageSource.getMessage(
                "notification.confirmable.senderAlert.body",
                new Object[]{confirmRate, alertThreshold, notification.getTitle()},
                "確認率が " + confirmRate + "% です（閾値: " + alertThreshold + "%）: " + notification.getTitle(),
                locale);
        eventPublisher.publishEvent(new ConfirmableReminderNotificationEvent(
                new NotificationDeliveryRequest(
                        senderId,
                        "CONFIRMABLE_NOTIFICATION_SENDER_ALERT",
                        NotificationPriority.HIGH,
                        title,
                        body,
                        "CONFIRMABLE_NOTIFICATION",
                        notification.getId(),
                        toNotificationScopeType(notification),
                        notification.getScopeId(),
                        notification.getActionUrl(),
                        null),
                notification.getId()));
        log.info("送信者アラートを確定: notificationId={}, confirmRate={}%, threshold={}%",
                notification.getId(), confirmRate, alertThreshold);
        return true;
    }

    private NotificationScopeType toNotificationScopeType(ConfirmableNotificationEntity notification) {
        return switch (notification.getScopeType()) {
            case TEAM -> NotificationScopeType.TEAM;
            case ORGANIZATION -> NotificationScopeType.ORGANIZATION;
            case PLATFORM -> NotificationScopeType.SYSTEM;
            case COMMITTEE -> NotificationScopeType.COMMITTEE;
        };
    }

    private NotificationPriority toNotificationPriority(ConfirmableNotificationEntity notification) {
        return switch (notification.getPriority()) {
            case URGENT -> NotificationPriority.URGENT;
            case HIGH -> NotificationPriority.HIGH;
            case NORMAL -> NotificationPriority.NORMAL;
        };
    }
}
