package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
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
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    /** Issue #2715 ロットB: 送信者アラート本文の locale 解決（D-5: auth の UserRepository を直接呼ばない）。 */
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;
    private final JdbcTemplate jdbcTemplate;

    /** リマインドの1バッチあたりの受信者数上限（軍議第8版確定稿 §9.4: 「受信者500人ごと」）。 */
    static final int REMINDER_BATCH_SIZE = 500;

    /**
     * リマインドバッチを実行する。
     *
     * <p>1分間隔で起動し、ACTIVE 状態の通知を対象にリマインド送信・アラート送信を行う。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。確認通知の未確認者リマインド送信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "notification-confirmable-reminder", description = "確認通知の未確認受信者リマインドを 1 分毎に送信する")
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
                Locale locale = Locale.forLanguageTag(
                        userLocaleCache.getLocale(notification.getCreatedBy().getId()));
                String title = messageSource.getMessage(
                        "notification.confirmable.senderAlert.title", null, "確認通知のアラート", locale);
                String body = messageSource.getMessage(
                        "notification.confirmable.senderAlert.body",
                        new Object[]{confirmRate, alertThreshold, notification.getTitle()},
                        "確認率が " + confirmRate + "% です（閾値: " + alertThreshold + "%）: " + notification.getTitle(),
                        locale);
                notificationHelper.notify(
                        notification.getCreatedBy().getId(),
                        "CONFIRMABLE_NOTIFICATION_SENDER_ALERT",
                        NotificationPriority.HIGH,
                        title,
                        body,
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

    /**
     * CMP-260920-1040: 通知1件・受信者500人ごとに、1トランザクションで
     * 「条件付きUPDATEで送信済みを確定→確定できた人だけ notifications を多値INSERT」を行う
     * （軍議第8版確定稿 §9.4）。
     *
     * <p>手順:
     * <ol>
     *   <li>対象者の {@code first_reminder_sent_at}（2回目なら {@code second}）を
     *       {@code … IS NULL AND is_confirmed = false AND excluded_at IS NULL} の条件付き UPDATE で確定する。
     *       実装は「{@code SELECT ... FOR UPDATE} で同じ条件を満たす行をロック・取得 → ロック済みの行だけ
     *       UPDATE」という形を取る。ロック済みの行に対する UPDATE は、他トランザクションがこの間に割り込めない
     *       ため、条件付き UPDATE と等価である</li>
     *   <li>UPDATE で確定できた人の ID を取り出す（ロックした時点で取れている）</li>
     *   <li>その人たちの分だけ notifications を多値 INSERT する</li>
     * </ol>
     * 手順3が失敗したら手順1もロールバックされ、次回バッチで再送される（AC-57）。</p>
     *
     * @param notificationId  対象の確認通知 ID
     * @param candidateUserIds 候補の受信者 user_id（最大500件を1トランザクションで扱う契約）
     * @param isFirstReminder true なら1回目リマインド、false なら2回目
     * @param now             現在日時
     * @return 実際に送信を確定した user_id（UPDATE で確定できた人だけ）
     */
    @Transactional
    public List<Long> processRemindersTransactional(
            Long notificationId, List<Long> candidateUserIds, boolean isFirstReminder, LocalDateTime now) {
        if (candidateUserIds == null || candidateUserIds.isEmpty()) {
            return List.of();
        }

        // 手順1〜2: ロックを取ったうえで、経過時間・期限の条件（needsFirstReminder/needsSecondReminder）を
        // 満たす行だけを確定対象とする。ロック済みの行への UPDATE は他トランザクションが割り込めないため
        // 条件付き UPDATE と等価（軍議第8版確定稿 §9.4）。
        List<ConfirmableNotificationRecipientEntity> lockedCandidates = isFirstReminder
                ? recipientRepository.findFirstReminderCandidatesForUpdate(notificationId, candidateUserIds)
                : recipientRepository.findSecondReminderCandidatesForUpdate(notificationId, candidateUserIds);

        List<ConfirmableNotificationRecipientEntity> confirmed = new ArrayList<>();
        for (ConfirmableNotificationRecipientEntity recipient : lockedCandidates) {
            boolean eligible = isFirstReminder ? recipient.needsFirstReminder(now) : recipient.needsSecondReminder(now);
            if (eligible) {
                if (isFirstReminder) {
                    recipient.markFirstReminderSent();
                } else {
                    recipient.markSecondReminderSent();
                }
                confirmed.add(recipient);
            }
        }
        if (confirmed.isEmpty()) {
            return List.of();
        }
        recipientRepository.saveAll(confirmed);

        // 手順3: notification 本体を読み、確定した人たちの分だけ notifications を多値 INSERT する。
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException(
                        "リマインドバッチ: 確認通知が見つからない notificationId=" + notificationId));
        List<Long> confirmedUserIds = confirmed.stream().map(r -> r.getUser().getId()).toList();
        insertReminderNotifications(notification, confirmedUserIds, isFirstReminder);

        return confirmedUserIds;
    }

    /** リマインド用の notifications 多値 INSERT（受信者数に比例した文数にしない・§9.4）。 */
    private void insertReminderNotifications(
            ConfirmableNotificationEntity notification, List<Long> userIds, boolean isFirstReminder) {
        if (userIds.isEmpty()) {
            return;
        }
        String notificationType = isFirstReminder
                ? "CONFIRMABLE_NOTIFICATION_REMINDER_1" : "CONFIRMABLE_NOTIFICATION_REMINDER_2";
        String scopeTypeStr = notification.getScopeType() == null ? null : notification.getScopeType().name();
        StringBuilder sql = new StringBuilder(
                "INSERT INTO notifications (user_id, organization_id, notification_type, priority, title, body, "
                        + "source_type, source_id, scope_type, scope_id, action_url, actor_id, is_read, created_at) VALUES ");
        Object[] args = new Object[userIds.size() * 12];
        int a = 0;
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,?,?,?,?,?,?,?,?,?,0,UTC_TIMESTAMP())");
            args[a++] = userIds.get(i);
            args[a++] = null;
            args[a++] = notificationType;
            args[a++] = notification.getPriority() == null ? null : notification.getPriority().name();
            args[a++] = notification.getTitle();
            args[a++] = notification.getBody() != null ? notification.getBody() : "";
            args[a++] = "CONFIRMABLE_NOTIFICATION";
            args[a++] = notification.getId();
            args[a++] = scopeTypeStr;
            args[a++] = notification.getScopeId();
            args[a++] = notification.getActionUrl();
            args[a++] = null;
        }
        jdbcTemplate.update(sql.toString(), args);
    }
}
