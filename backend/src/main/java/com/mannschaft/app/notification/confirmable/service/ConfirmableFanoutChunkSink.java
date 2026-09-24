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
 * <p><b>課金の猶予超過（AC-24・AC-25・AC-27）について</b>: {@link NotificationCreditService#consume} は
 * 受信者行・notifications の多値 INSERT の<b>後</b>に、{@code @Transactional}（{@code REQUIRED}）のまま
 * 素朴に呼ぶ（このメソッドと同一物理トランザクションに参加させる）。猶予超過
 * （{@code BusinessException(CREDIT_INSUFFICIENT)}）で失敗した場合、直前の INSERT も含めてこのメソッド自身の
 * トランザクション全体を通常どおりロールバックさせて呼び出し元へ例外をそのまま伝播させる
 * （{@code ConfirmableFanoutChunkSinkCreditRollbackIT} が processChunk からの例外伝播を要求しており、
 * 受信者行・notifications・課金のいずれも作られない状態に戻る＝AC-24 と同じ「丸ごとロールバック」）。
 * {@code delivery_status=PARTIALLY_FAILED}（AC-25）は、その例外を投げる<b>前</b>に
 * {@link #requiresNewTxTemplate}（{@code REQUIRES_NEW}）の別の独立した物理トランザクションで確定する。
 * 参加トランザクション側がロールバックされても、独立トランザクション側は既にコミット済みのため残る。</p>
 *
 * <p><b>ジョブ行の DONE 化について（§9.2 の関所）</b>: 親行の状態確定とジョブの DONE 化は
 * {@link #finish} の<b>同一トランザクション</b>で行う（{@code REQUIRES_NEW} は使わない）。
 * {@link NotificationFanoutJobService#markDoneInCallerTransaction} を呼び出し元の TX にそのまま
 * 参加させる。試練の IT はいずれも {@code finish} を呼ぶ前に {@code notification_fanout_jobs} 行を
 * 用意してから jobId を渡す（是正: ジョブ行が存在しない前提の旧実装から修正）。</p>
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
    private final JdbcTemplate jdbcTemplate;
    private final MessageSource messageSource;
    /** §9.2: finish で親の状態確定と同一トランザクションでジョブを DONE にするために使う。 */
    private final NotificationFanoutJobService fanoutJobService;
    /** {@code consume} を別物理トランザクションで呼ぶためのテンプレート（クラス javadoc 参照）。 */
    private final TransactionTemplate requiresNewTxTemplate;

    @Value("${app.base-url}")
    private String baseUrl;

    public ConfirmableFanoutChunkSink(ConfirmableNotificationRepository notificationRepository,
            NotificationCreditService creditService,
            EmailOutboxService emailOutboxService,
            JdbcTemplate jdbcTemplate,
            MessageSource messageSource,
            NotificationFanoutJobService fanoutJobService,
            PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.creditService = creditService;
        this.emailOutboxService = emailOutboxService;
        this.jdbcTemplate = jdbcTemplate;
        this.messageSource = messageSource;
        this.fanoutJobService = fanoutJobService;
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

        // 受信者行の多値 INSERT（確認トークン付き）。重複があればここで一意制約違反として例外になる（AC-24。
        // この例外はこのメソッド自身のトランザクションをそのままロールバックさせて呼び出し元へ伝播する。
        // consume はまだ呼んでいないため課金は最初から発生しない＝AC-24/AC-27 の「課金も作られない」を満たす）。
        Map<Long, String> tokensByUserId = insertRecipients(notification, newUserIds);

        // notifications への多値 INSERT。
        insertAppNotifications(notification, newUserIds);

        // 課金の消費（AC-24・AC-25・AC-27）。ここでは意図的に consume を REQUIRED のまま呼び、
        // このメソッド自身の物理トランザクションに参加させる。猶予超過（CREDIT_INSUFFICIENT）で
        // 失敗した場合、直前の受信者行・notifications の INSERT も含めてこのチャンクの
        // トランザクション全体をロールバックさせたいため（AC-24 と同じ「丸ごとロールバック」の形）。
        //
        // 試練（ConfirmableFanoutChunkSinkCreditRollbackIT#secondChunkExceedsGraceButFirstChunkStays）は
        // processChunk が BusinessException(CREDIT_INSUFFICIENT) を<b>呼び出し元へそのまま投げる</b>ことを
        // 要求している（catch して ChunkResult(stopped=true) を返す設計は red になる）。
        // delivery_status=PARTIALLY_FAILED（AC-25）は、例外を投げる前に<b>別の独立トランザクション</b>
        // （{@link #requiresNewTxTemplate}）で確定させる。参加トランザクション（このメソッド自身）は
        // consume の例外で rollback-only になっており、そのまま例外を伝播させれば通常どおり
        // ロールバックされる（受信者行・notifications・課金のすべてが無かったことになる）。
        if (notification.getScopeType() == ScopeType.ORGANIZATION) {
            try {
                creditService.consume(notification.getScopeId(), newUserIds.size(), NotificationSourceType.CONFIRMABLE);
            } catch (BusinessException ex) {
                if (ex.getErrorCode() == NotificationCreditErrorCode.CREDIT_INSUFFICIENT) {
                    requiresNewTxTemplate.executeWithoutResult(status -> {
                        ConfirmableNotificationEntity locked = notificationRepository.findByIdForUpdate(notificationId)
                                .orElseThrow();
                        locked.markPartiallyFailed();
                        notificationRepository.save(locked);
                    });
                }
                throw ex;
            }
        }

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

        // §9.2: 親行の状態確定と同一トランザクションでジョブを DONE にする（REQUIRES_NEW は使わない）。
        fanoutJobService.markDoneInCallerTransaction(jobId);
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
        Long actorId = notification.getCreatedByUserId();
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
                    // AC-29/AC-46/AC-47 の試練は source_event_id = notificationId（文字列）で email_outbox を
                    // 検索する。冪等性（ユーザー単位の重複防止）は idempotencyKey（null=自動生成。
                    // userId・templateKind・sourceEventId から導出されるため、この列が全員同じでも
                    // userId で一意になる）で担保する。sourceEventId 自体をユーザー単位にする必要は無い。
                    String.valueOf(notification.getId()),
                    null,
                    userId,
                    notification.getScopeType() == ScopeType.ORGANIZATION ? notification.getScopeId() : null));
        }
        if (!requests.isEmpty()) {
            emailOutboxService.enqueueAll(requests);
        }
    }

    /**
     * 確認メールの本文を組み立てる（{@code ConfirmableNotificationEmailEventListener} の同期経路は
     * Thymeleaf テンプレートを使うが、非同期 fanout はチャンクごとに数百〜数千通発行しうるため、
     * テンプレートエンジンを介さない軽量な HTML 組み立てに留める。文言は同じ {@code MessageSource}
     * キー群を使うため6言語分の翻訳資産はそのまま再利用する）。
     */
    private String renderPlainConfirmEmail(String confirmUrl, Locale locale) {
        String bodyMessage = getMessage("email.confirmableNotification.body", locale);
        String buttonLabel = getMessage("email.confirmableNotification.button", locale);
        String expiryMessage = getMessage("email.confirmableNotification.expiry", locale);
        String ignoreMessage = getMessage("email.confirmableNotification.ignore", locale);
        String footerMessage = getMessage("email.common.footer", locale);
        return "<p>" + escapeHtml(bodyMessage) + "</p>"
                + "<p><a href=\"" + confirmUrl + "\">" + escapeHtml(buttonLabel) + "</a></p>"
                + "<p>" + escapeHtml(expiryMessage) + "</p>"
                + "<p>" + escapeHtml(ignoreMessage) + "</p>"
                + "<p>" + escapeHtml(footerMessage) + "</p>";
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String getMessage(String key, Locale locale) {
        return messageSource.getMessage(key, null, key, locale);
    }

    private Locale resolveLocale(String localeTag) {
        try {
            if (localeTag != null && !localeTag.isBlank()) {
                return Locale.forLanguageTag(localeTag.replace("_", "-"));
            }
        } catch (Exception e) {
            log.debug("ロケール解決失敗。日本語にフォールバック: localeTag={}", localeTag, e);
        }
        return Locale.JAPANESE;
    }
}
