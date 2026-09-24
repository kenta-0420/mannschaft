package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.credit.entity.NotificationSourceType;
import com.mannschaft.app.notification.credit.error.NotificationCreditErrorCode;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.notification.fanout.FanoutChunkSink;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CMP-260920-1040: 確認通知の fan-out チャンク出力先（軍議第8版確定稿 §3.4・§8.1〜§8.4・§9.2・§10・§11）。
 *
 * <p>1チャンクの処理契約:
 * <ol>
 *   <li>既に受信者行がある user_id を除いた新規分だけを求める（再開時の二重防止・AC-23）</li>
 *   <li>新規分だけ、確認トークン付きで受信者行を多値 INSERT する</li>
 *   <li>新規分だけ notifications を多値 INSERT する</li>
 *   <li>組織スコープなら新規分だけ consume する</li>
 *   <li>total_recipient_count / delivered_count / unconfirmed_count に加算し、
 *       delivery_status を DELIVERING にする</li>
 *   <li>新規分だけメール outbox へ {@link EmailOutboxService#enqueueAll} で登録する</li>
 * </ol>
 * 親の行を扱うトランザクションは、最初の読み取りを {@code findByIdForUpdate} にする（軍議第8版確定稿 §11.1）。</p>
 *
 * <p><b>課金の猶予超過（AC-25）について</b>: {@link NotificationCreditService#consume} は
 * {@code @Transactional}（{@code REQUIRED}）であり、本クラスの {@link #processChunk} と同一物理トランザクションに
 * 参加する。素朴に呼ぶと、猶予超過時にその物理トランザクションが rollback-only になり、以後は
 * どう振る舞っても {@code UnexpectedRollbackException} でしかコミットできなくなる（参加トランザクションの
 * 制約）。そこで {@code consume} は {@link #consumeCreditTxTemplate}（{@code REQUIRES_NEW}）で
 * 明示的に<b>別の物理トランザクション</b>として呼ぶ。猶予超過はその別トランザクションの中だけで
 * ロールバックし、{@link #processChunk} 自身のトランザクション（親行のロックのみ保持・まだ何も
 * INSERT していない）は無傷のまま残るため、同一トランザクション内で {@code delivery_status} を
 * PARTIALLY_FAILED に確定してコミットできる（AC-25）。</p>
 *
 * <p><b>ジョブ行の DONE 化について</b>: {@link #finish} は確認通知の親行・受信者の状態確定と
 * fan-out ジョブの {@code DONE} 遷移を<b>同一トランザクション</b>で行う（{@link NotificationFanoutJobService
 * #markDoneInCallerTransaction}。軍議第8版確定稿 §9.2 の「関所」）。</p>
 */
@Slf4j
@Service
public class ConfirmableFanoutChunkSink implements FanoutChunkSink {

    /** {@link com.mannschaft.app.notification.fanout.NotificationFanoutJob#getNotificationType()} と一致させるキー。 */
    public static final String NOTIFICATION_TYPE = "CONFIRMABLE_NOTIFICATION_FANOUT";

    private static final String SOURCE_TYPE = "CONFIRMABLE_NOTIFICATION";
    private static final String APP_NOTIFICATION_TYPE = "CONFIRMABLE_NOTIFICATION";
    private static final String EMAIL_TEMPLATE_KIND = "NOTIFICATION_CONFIRM";

    /** リマインド既定分数（{@code ConfirmableNotificationService} と同値。通知個別・スコープ設定のいずれも無いときのフォールバック）。 */
    private static final int DEFAULT_FIRST_REMINDER_MINUTES = 180;
    private static final int DEFAULT_SECOND_REMINDER_MINUTES = 120;

    private final ConfirmableNotificationRepository notificationRepository;
    private final NotificationCreditService creditService;
    private final EmailOutboxService emailOutboxService;
    private final NotificationFanoutJobService jobService;
    private final JdbcTemplate jdbcTemplate;
    private final MessageSource messageSource;
    /** {@code consume} を別物理トランザクションで呼ぶためのテンプレート（クラス javadoc 参照）。 */
    private final TransactionTemplate consumeCreditTxTemplate;

    @Value("${app.base-url}")
    private String baseUrl;

    public ConfirmableFanoutChunkSink(ConfirmableNotificationRepository notificationRepository,
            NotificationCreditService creditService,
            EmailOutboxService emailOutboxService,
            NotificationFanoutJobService jobService,
            JdbcTemplate jdbcTemplate,
            MessageSource messageSource,
            PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.creditService = creditService;
        this.emailOutboxService = emailOutboxService;
        this.jobService = jobService;
        this.jdbcTemplate = jdbcTemplate;
        this.messageSource = messageSource;
        this.consumeCreditTxTemplate = new TransactionTemplate(transactionManager);
        this.consumeCreditTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String notificationType() {
        return NOTIFICATION_TYPE;
    }

    @Override
    @Transactional
    public ChunkResult processChunk(UUID jobId, Long notificationId, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new ChunkResult(0, false);
        }

        // §9.2/§11.1: 親の行を最初の読み取りで FOR UPDATE ロックする。
        ConfirmableNotificationEntity notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new IllegalStateException("確認通知が見つかりません: id=" + notificationId));

        // §8.3: CANCELLED/EXPIRED ならこのチャンクを打ち切る（新規受信者は作らない）。
        if (notification.getStatus() == ConfirmableNotificationStatus.CANCELLED
                || notification.getStatus() == ConfirmableNotificationStatus.EXPIRED) {
            return new ChunkResult(0, true);
        }

        // 既に受信者行がある user_id を除く（AC-23 再開時の冪等性）。多重INSERTで壊れる重複はここでは
        // 除去しない（AC-24: チャンク内の重複は実処理由来の一意制約違反として現れる契約）。
        Set<Long> existing = findExistingRecipientUserIds(notificationId, userIds);
        List<Long> newUserIds = new ArrayList<>();
        for (Long userId : userIds) {
            if (!existing.contains(userId)) {
                newUserIds.add(userId);
            }
        }
        if (newUserIds.isEmpty()) {
            return new ChunkResult(0, false);
        }

        // 課金の消費を INSERT より先に、別物理トランザクション（REQUIRES_NEW）で行う（AC-25。クラス javadoc 参照）。
        // 猶予超過（CREDIT_INSUFFICIENT）はその別トランザクションの中だけでロールバックするため、
        // このメソッド自身のトランザクション（親行のロックのみ・まだ何も INSERT していない）は無傷のまま残る。
        if (notification.getScopeType() == ScopeType.ORGANIZATION) {
            try {
                consumeCreditTxTemplate.executeWithoutResult(status ->
                        creditService.consume(notification.getScopeId(), newUserIds.size(), NotificationSourceType.CONFIRMABLE));
            } catch (BusinessException ex) {
                if (ex.getErrorCode() == NotificationCreditErrorCode.CREDIT_INSUFFICIENT) {
                    // このメソッドのトランザクションは無傷（rollback-only になっていない）ため、
                    // 同一トランザクション内で PARTIALLY_FAILED を確定してコミットできる（AC-25）。
                    notification.markPartiallyFailed();
                    notificationRepository.save(notification);
                    return new ChunkResult(0, true);
                }
                throw ex;
            }
        }

        // 受信者行の多値 INSERT（確認トークン付き）。重複があればここで一意制約違反として例外になる（AC-24）。
        Map<Long, String> tokensByUserId = insertRecipients(notification, newUserIds);

        // notifications への多値 INSERT。
        insertAppNotifications(notification, newUserIds);

        // カウンタ更新・状態遷移（同じロック済み行にドメインメソッドで反映）。
        notification.addDeliveredCount(newUserIds.size());
        notification.addUnconfirmedCount(newUserIds.size());
        notification.markDelivering();
        notificationRepository.save(notification);

        // メール outbox 登録（新規分だけ）。
        enqueueEmails(notification, newUserIds, tokensByUserId);

        return new ChunkResult(newUserIds.size(), false);
    }

    @Override
    @Transactional
    public void finish(UUID jobId, Long notificationId) {
        ConfirmableNotificationEntity notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new IllegalStateException("確認通知が見つかりません: id=" + notificationId));

        if (notification.getStatus() == ConfirmableNotificationStatus.CANCELLED
                || notification.getStatus() == ConfirmableNotificationStatus.EXPIRED) {
            notification.markStopped();
        } else if (notification.getDeliveryStatus() == ConfirmableNotificationDeliveryStatus.PARTIALLY_FAILED) {
            // 既に課金の猶予超過で PARTIALLY_FAILED が確定済み（processChunk が別チャンクで先に確定）。
            // finish はこれを上書きしない。
        } else {
            notification.markDelivered();
            if (notification.getStatus() == ConfirmableNotificationStatus.ACTIVE
                    && notification.isReadyToComplete()) {
                notification.complete();
            }
        }
        notificationRepository.save(notification);

        // 軍議第8版確定稿 §9.2: 親の状態確定とジョブの DONE 化を同一トランザクションで行う（REQUIRES_NEW は使わない）。
        jobService.markDoneInCallerTransaction(jobId);
    }

    // -------------------------------------------------------------------------
    // 内部処理
    // -------------------------------------------------------------------------

    private Set<Long> findExistingRecipientUserIds(Long notificationId, List<Long> userIds) {
        Set<Long> distinctCandidates = new HashSet<>(userIds);
        if (distinctCandidates.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", distinctCandidates.stream().map(id -> "?").toList());
        Object[] args = new Object[distinctCandidates.size() + 1];
        args[0] = notificationId;
        int i = 1;
        for (Long id : distinctCandidates) {
            args[i++] = id;
        }
        List<Long> rows = jdbcTemplate.query(
                "SELECT user_id FROM confirmable_notification_recipients "
                        + "WHERE confirmable_notification_id = ? AND user_id IN (" + placeholders + ")",
                (rs, rowNum) -> rs.getLong("user_id"), args);
        return new HashSet<>(rows);
    }

    private Map<Long, String> insertRecipients(ConfirmableNotificationEntity notification, List<Long> newUserIds) {
        int[] resolved = resolveReminderMinutes(notification);
        Map<Long, String> tokens = new HashMap<>();
        StringBuilder sql = new StringBuilder(
                "INSERT INTO confirmable_notification_recipients "
                        + "(confirmable_notification_id, user_id, confirm_token, is_confirmed, "
                        + "resolved_first_reminder_minutes, resolved_second_reminder_minutes, created_at) VALUES ");
        Object[] args = new Object[newUserIds.size() * 5];
        int a = 0;
        for (int i = 0; i < newUserIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,0,?,?,UTC_TIMESTAMP())");
            Long userId = newUserIds.get(i);
            String token = UUID.randomUUID().toString();
            tokens.put(userId, token);
            args[a++] = notification.getId();
            args[a++] = userId;
            args[a++] = token;
            args[a++] = resolved[0];
            args[a++] = resolved[1];
        }
        jdbcTemplate.update(sql.toString(), args);
        return tokens;
    }

    /**
     * リマインド分数の3段フォールバック解決（{@code ConfirmableNotificationService.send} と同じ規則）。
     * 1. 通知個別設定 → 2. スコープ設定 → 3. システムデフォルト（1回目180分／2回目120分）。
     */
    private int[] resolveReminderMinutes(ConfirmableNotificationEntity notification) {
        List<Integer[]> rows = jdbcTemplate.query(
                "SELECT default_first_reminder_minutes, default_second_reminder_minutes "
                        + "FROM confirmable_notification_settings WHERE scope_type = ? AND scope_id = ?",
                (rs, rowNum) -> new Integer[] {
                        rs.getObject(1, Integer.class), rs.getObject(2, Integer.class)},
                notification.getScopeType().name(), notification.getScopeId());
        Integer defaultFirst = rows.isEmpty() ? null : rows.get(0)[0];
        Integer defaultSecond = rows.isEmpty() ? null : rows.get(0)[1];
        int resolvedFirst = notification.getFirstReminderMinutes() != null
                ? notification.getFirstReminderMinutes()
                : (defaultFirst != null ? defaultFirst : DEFAULT_FIRST_REMINDER_MINUTES);
        int resolvedSecond = notification.getSecondReminderMinutes() != null
                ? notification.getSecondReminderMinutes()
                : (defaultSecond != null ? defaultSecond : DEFAULT_SECOND_REMINDER_MINUTES);
        return new int[] {resolvedFirst, resolvedSecond};
    }

    private void insertAppNotifications(ConfirmableNotificationEntity notification, List<Long> newUserIds) {
        String scopeTypeStr = notification.getScopeType() == null ? null : notification.getScopeType().name();
        Long actorId = notification.getCreatedBy() == null ? null : notification.getCreatedBy().getId();
        StringBuilder sql = new StringBuilder(
                "INSERT INTO notifications (user_id, organization_id, notification_type, priority, title, body, "
                        + "source_type, source_id, scope_type, scope_id, action_url, actor_id, is_read, created_at) VALUES ");
        Object[] args = new Object[newUserIds.size() * 12];
        int a = 0;
        for (int i = 0; i < newUserIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,?,?,?,?,?,?,?,?,?,0,UTC_TIMESTAMP())");
            args[a++] = newUserIds.get(i);
            args[a++] = notification.getScopeType() == ScopeType.ORGANIZATION ? notification.getScopeId() : null;
            args[a++] = APP_NOTIFICATION_TYPE;
            args[a++] = notification.getPriority() == null ? null : notification.getPriority().name();
            args[a++] = notification.getTitle();
            args[a++] = notification.getBody() != null ? notification.getBody() : "";
            args[a++] = SOURCE_TYPE;
            args[a++] = notification.getId();
            args[a++] = scopeTypeStr;
            args[a++] = notification.getScopeId();
            args[a++] = notification.getActionUrl();
            args[a++] = actorId;
        }
        jdbcTemplate.update(sql.toString(), args);
    }

    private void enqueueEmails(ConfirmableNotificationEntity notification, List<Long> newUserIds,
            Map<Long, String> tokensByUserId) {
        if (newUserIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", newUserIds.stream().map(id -> "?").toList());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, email, locale FROM users WHERE id IN (" + placeholders + ")",
                newUserIds.toArray());

        List<EmailOutboxRequest> requests = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long userId = ((Number) row.get("id")).longValue();
            String email = (String) row.get("email");
            if (email == null || email.isBlank()) {
                // AC-48: メールアドレスが無い（空文字）受信者は outbox に登録しない。受信者行・課金は既に作成済み。
                continue;
            }
            String localeTag = row.get("locale") == null ? "ja" : row.get("locale").toString();
            String token = tokensByUserId.get(userId);
            if (token == null) {
                continue;
            }
            Locale locale = resolveLocale(localeTag);
            String confirmUrl = baseUrl + "/notifications/confirm/" + token;
            String subject = getMessage("email.confirmableNotification.subject", locale);
            String htmlBody = renderPlainConfirmEmail(confirmUrl, locale);
            requests.add(new EmailOutboxRequest(
                    EMAIL_TEMPLATE_KIND,
                    locale.toLanguageTag(),
                    email,
                    Map.of("subject", subject, "body", htmlBody),
                    SOURCE_TYPE,
                    "notif-confirm:" + notification.getId() + ":" + userId,
                    null,
                    userId,
                    notification.getScopeType() == ScopeType.ORGANIZATION ? notification.getScopeId() : null));
        }
        if (!requests.isEmpty()) {
            emailOutboxService.enqueueAll(requests);
        }
    }
}
