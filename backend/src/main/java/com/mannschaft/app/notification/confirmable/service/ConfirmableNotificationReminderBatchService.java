package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationSettingsRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * F04.9 確認通知リマインドバッチサービス。
 *
 * <p>1分間隔で ACTIVE 状態の確認通知を検索し、
 * 未確認受信者への1回目・2回目リマインド送信と送信者アラートを処理する。</p>
 *
 * <p>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmableNotificationReminderBatchService {

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final ConfirmableNotificationSettingsRepository settingsRepository;
    private final NotificationHelper notificationHelper;

    /**
     * リマインドバッチを実行する。
     *
     * <p>1分間隔で起動し、ACTIVE 状態の通知を対象にリマインド送信・アラート送信を行う。</p>
     */
    @Scheduled(fixedDelay = 60_000) // 1分間隔
    @SchedulerLock(
            name = "confirmableNotificationReminderBatch",
            lockAtLeastFor = "PT50S",
            lockAtMostFor = "PT2M")
    public void runBatch() {
        LocalDateTime now = LocalDateTime.now();
        log.debug("確認通知リマインドバッチ開始: {}", now);

        // STEP0: ACTIVE 状態の通知のみを対象にする（パフォーマンス最適化）
        List<ConfirmableNotificationEntity> activeNotifications =
                notificationRepository.findByStatus(ConfirmableNotificationStatus.ACTIVE);

        if (activeNotifications.isEmpty()) {
            return;
        }

        int reminderCount = 0;
        int alertCount = 0;

        for (ConfirmableNotificationEntity notification : activeNotifications) {
            try {
                reminderCount += processReminders(notification, now);
                boolean alerted = processAlertIfNeeded(notification);
                if (alerted) alertCount++;
            } catch (Exception e) {
                log.error("確認通知リマインドバッチ処理失敗: notificationId={}, error={}",
                        notification.getId(), e.getMessage());
            }
        }

        if (reminderCount > 0 || alertCount > 0) {
            log.info("確認通知リマインドバッチ完了: activeNotifications={}, reminders={}, alerts={}",
                    activeNotifications.size(), reminderCount, alertCount);
        }
    }

    /**
     * STEP1: 通知の未確認受信者へのリマインド送信処理。
     *
     * @param notification 対象の確認通知
     * @param now          現在日時
     * @return 送信したリマインド件数
     */
    @Transactional
    public int processReminders(ConfirmableNotificationEntity notification, LocalDateTime now) {
        // 未確認かつ除外されていない受信者を取得
        List<ConfirmableNotificationRecipientEntity> unconfirmedRecipients =
                recipientRepository.findActiveUnconfirmedByNotificationId(notification.getId());

        int sentCount = 0;
        NotificationScopeType scopeType = toNotificationScopeType(notification);
        NotificationPriority priority = toNotificationPriority(notification);

        for (ConfirmableNotificationRecipientEntity recipient : unconfirmedRecipients) {
            try {
                // 1回目リマインド判定と送信
                if (recipient.needsFirstReminder(now)) {
                    recipient.markFirstReminderSent();
                    recipientRepository.save(recipient);

                    notificationHelper.notify(
                            recipient.getUser().getId(),
                            "CONFIRMABLE_NOTIFICATION_REMINDER_1",
                            priority,
                            notification.getTitle(),
                            notification.getBody() != null ? notification.getBody() : "",
                            "CONFIRMABLE_NOTIFICATION",
                            notification.getId(),
                            scopeType,
                            notification.getScopeId(),
                            notification.getActionUrl(),
                            null);

                    sentCount++;
                    log.debug("1回目リマインド送信: notificationId={}, userId={}",
                            notification.getId(), recipient.getUser().getId());
                }
                // 2回目リマインド判定と送信
                else if (recipient.needsSecondReminder(now)) {
                    recipient.markSecondReminderSent();
                    recipientRepository.save(recipient);

                    notificationHelper.notify(
                            recipient.getUser().getId(),
                            "CONFIRMABLE_NOTIFICATION_REMINDER_2",
                            priority,
                            notification.getTitle(),
                            notification.getBody() != null ? notification.getBody() : "",
                            "CONFIRMABLE_NOTIFICATION",
                            notification.getId(),
                            scopeType,
                            notification.getScopeId(),
                            notification.getActionUrl(),
                            null);

                    sentCount++;
                    log.debug("2回目リマインド送信: notificationId={}, userId={}",
                            notification.getId(), recipient.getUser().getId());
                }
            } catch (Exception e) {
                log.warn("リマインド送信失敗（継続）: notificationId={}, userId={}, error={}",
                        notification.getId(), recipient.getUser().getId(), e.getMessage());
            }
        }

        return sentCount;
    }

    /**
     * STEP2: 確認率が閾値を下回った場合の送信者アラート処理。
     *
     * <p>確認率 = 確認済み件数 / 除外を除いた受信者数 × 100</p>
     *
     * @param notification 対象の確認通知
     * @return アラートを送信した場合 true
     */
    @Transactional
    public boolean processAlertIfNeeded(ConfirmableNotificationEntity notification) {
        // 送信者（作成者）が存在しない場合はスキップ
        if (notification.getCreatedBy() == null) {
            return false;
        }

        // スコープ設定からアラート閾値を取得
        int alertThreshold = settingsRepository
                .findByScopeTypeAndScopeId(notification.getScopeType(), notification.getScopeId())
                .map(ConfirmableNotificationSettingsEntity::getSenderAlertThresholdPercent)
                .orElse(80); // デフォルト 80%

        // 確認率の計算
        long confirmedCount = recipientRepository
                .countByConfirmableNotificationIdAndIsConfirmedTrue(notification.getId());
        long totalActiveCount = recipientRepository
                .countByConfirmableNotificationIdAndExcludedAtIsNull(notification.getId());

        if (totalActiveCount == 0) {
            return false;
        }

        int confirmRate = (int) (confirmedCount * 100L / totalActiveCount);

        // 確認率が閾値を下回っている場合に送信者へアラート通知
        if (confirmRate < alertThreshold) {
            try {
                NotificationScopeType scopeType = toNotificationScopeType(notification);
                notificationHelper.notify(
                        notification.getCreatedBy().getId(),
                        "CONFIRMABLE_NOTIFICATION_SENDER_ALERT",
                        NotificationPriority.HIGH,
                        "確認通知のアラート",
                        "確認率が " + confirmRate + "% です（閾値: " + alertThreshold + "%）: " + notification.getTitle(),
                        "CONFIRMABLE_NOTIFICATION",
                        notification.getId(),
                        scopeType,
                        notification.getScopeId(),
                        notification.getActionUrl(),
                        null);

                log.info("送信者アラート送信: notificationId={}, confirmRate={}%, threshold={}%",
                        notification.getId(), confirmRate, alertThreshold);
                return true;
            } catch (Exception e) {
                log.warn("送信者アラート送信失敗: notificationId={}, error={}",
                        notification.getId(), e.getMessage());
            }
        }

        return false;
    }

    // =========================================================================
    // プライベートヘルパーメソッド
    // =========================================================================

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
