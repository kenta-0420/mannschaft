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
import com.mannschaft.app.notification.entity.NotificationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 確認通知リマインドの「受信者チャンク1つぶん」「通知1件ぶんのアラート判定」を独立トランザクション
 * （{@link Propagation#REQUIRES_NEW}）で実行する Bean（CMP-260925-0905）。
 *
 * <h2>是正前の欠陥</h2>
 * <ul>
 *   <li>{@code ConfirmableNotificationReminderBatchService#runBatch} が同一クラスの {@code @Transactional}
 *       メソッドを自己呼び出ししており、トランザクションが張られず {@code recipient.user} の遅延読み込みが失敗した</li>
 *   <li>通知1件を1トランザクションにまとめると、1人の DB 例外で他の受信者の記録まで巻き戻る</li>
 * </ul>
 *
 * <h2>方針</h2>
 * <p>「送信済み日時の確定」と「通知行（{@code notifications}）の作成」を同一トランザクションで行う
 * （軍議第8版確定稿 §9.4・AC-57）。通知行は受信箱に残る永続データなので、非同期の配信プールの
 * 状態（飽和・拒否）に左右されてはならない。非同期にしてよいのは WebSocket / Push の配信だけであり、
 * それは本 Bean が返す {@link ReminderCommit} を使って、<b>commit 後</b>に非トランザクションの
 * オーケストレータが起動する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmableNotificationReminderRunner {

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final ConfirmableNotificationSettingsRepository settingsRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * commit 済みのリマインド結果。
     *
     * @param userIds       送信を確定した受信者の user_id
     * @param notifications 作成した通知行（採番 id 付き。commit 後の WebSocket / Push 配信に使う）
     */
    public record ReminderCommit(List<Long> userIds, List<NotificationEntity> notifications) {
        public static ReminderCommit empty() {
            return new ReminderCommit(List.of(), List.of());
        }
    }

    /**
     * CMP-260920-1040: 通知1件・受信者チャンクごとに、1トランザクションで
     * 「ロックして送信済みを確定→確定できた人だけ notifications を多値INSERT」を行う
     * （軍議第8版確定稿 §9.4）。手順3が失敗したら手順1もロールバックされ、次回バッチで再送される（AC-57）。
     *
     * @param notificationId   対象の確認通知 ID
     * @param candidateUserIds 候補の受信者 user_id（最大500件を1トランザクションで扱う契約）
     * @param isFirstReminder  true なら1回目リマインド、false なら2回目
     * @param now              現在日時
     * @return commit される結果（空なら何も確定していない）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReminderCommit commitReminders(
            Long notificationId, List<Long> candidateUserIds, boolean isFirstReminder, LocalDateTime now) {
        if (candidateUserIds == null || candidateUserIds.isEmpty()) {
            return ReminderCommit.empty();
        }

        // 手順1〜2: ロックを取ったうえで、経過時間・期限の条件を満たす行だけを確定対象とする。
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
            return ReminderCommit.empty();
        }
        recipientRepository.saveAll(confirmed);

        // 手順3: notification 本体を読み、確定した人たちの分だけ notifications を多値 INSERT する。
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException(
                        "リマインドバッチ: 確認通知が見つからない notificationId=" + notificationId));
        List<Long> confirmedUserIds = confirmed.stream().map(ConfirmableNotificationRecipientEntity::getRecipientUserId).toList();
        List<NotificationEntity> inserted = insertReminderNotifications(notification, confirmedUserIds, isFirstReminder);
        return new ReminderCommit(confirmedUserIds, inserted);
    }

    /** リマインド用の notifications 多値 INSERT（受信者数に比例した文数にしない・§9.4）。採番 id を戻す。 */
    private List<NotificationEntity> insertReminderNotifications(
            ConfirmableNotificationEntity notification, List<Long> userIds, boolean isFirstReminder) {
        String notificationType = isFirstReminder
                ? "CONFIRMABLE_NOTIFICATION_REMINDER_1" : "CONFIRMABLE_NOTIFICATION_REMINDER_2";
        NotificationScopeType scopeType = toNotificationScopeType(notification);
        NotificationPriority priority = toNotificationPriority(notification);
        String body = notification.getBody() != null ? notification.getBody() : "";

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
            args[a++] = priority.name();
            args[a++] = notification.getTitle();
            args[a++] = body;
            args[a++] = "CONFIRMABLE_NOTIFICATION";
            args[a++] = notification.getId();
            args[a++] = scopeType.name();
            args[a++] = notification.getScopeId();
            args[a++] = notification.getActionUrl();
            args[a++] = null;
        }
        final String sqlText = sql.toString();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sqlText, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);

        List<Map<String, Object>> keyRows = keyHolder.getKeyList();
        LocalDateTime createdAt = LocalDateTime.now();
        List<NotificationEntity> result = new ArrayList<>(userIds.size());
        for (int i = 0; i < userIds.size(); i++) {
            Long id = null;
            if (keyRows.size() == userIds.size()) {
                Map<String, Object> row = keyRows.get(i);
                Object value = row.get("GENERATED_KEY");
                if (value == null && !row.isEmpty()) {
                    value = row.values().iterator().next();
                }
                id = value == null ? null : ((Number) value).longValue();
            }
            result.add(NotificationEntity.builder()
                    .id(id)
                    .userId(userIds.get(i))
                    .notificationType(notificationType)
                    .priority(priority)
                    .title(notification.getTitle())
                    .body(body)
                    .sourceType("CONFIRMABLE_NOTIFICATION")
                    .sourceId(notification.getId())
                    .scopeType(scopeType)
                    .scopeId(notification.getScopeId())
                    .actionUrl(notification.getActionUrl())
                    .isRead(false)
                    .createdAt(createdAt)
                    .build());
        }
        return result;
    }

    /**
     * 送信者アラートの判定結果（確認率が閾値を下回った通知の、アラート文面の素材）。
     * 文面（ロケール解決）はトランザクション外のオーケストレータが組み立てる。
     */
    public record SenderAlertContext(Long senderId, Long notificationId, String notificationTitle,
                                     int confirmRate, int alertThreshold,
                                     NotificationScopeType scopeType, Long scopeId, String actionUrl) {
    }

    /**
     * 確認率が閾値を下回っているかを判定する（読み取りのみ）。
     *
     * <p>確認率 = 確認済み件数 / 除外を除いた受信者数 × 100。ロケール解決（auth ドメインの
     * {@code UserLocaleCache}）と通知の作成は、トランザクションを持たない呼び出し元が行う
     * （トランザクション入口から他ドメイン Repository へ到達させない）。</p>
     *
     * @param notificationId 確認通知ID
     * @return 閾値を下回っていればアラートの素材
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<SenderAlertContext> evaluateSenderAlert(Long notificationId) {
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null || notification.getStatus() != ConfirmableNotificationStatus.ACTIVE
                || notification.getCreatedByUserId() == null) {
            return Optional.empty();
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
            return Optional.empty();
        }
        int confirmRate = (int) (confirmedCount * 100L / totalActiveCount);
        if (confirmRate >= alertThreshold) {
            return Optional.empty();
        }
        return Optional.of(new SenderAlertContext(
                notification.getCreatedByUserId(), notification.getId(), notification.getTitle(),
                confirmRate, alertThreshold,
                toNotificationScopeType(notification), notification.getScopeId(), notification.getActionUrl()));
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
