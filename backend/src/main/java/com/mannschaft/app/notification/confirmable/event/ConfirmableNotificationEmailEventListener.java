package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F04.9 確認通知メール送信イベントリスナー。
 *
 * <p>{@link ConfirmableNotificationCreatedEvent} をトランザクションコミット後に受け取り、
 * 各受信者へ確認URLを含むメールを非同期で送信する。</p>
 *
 * <p>確認URL形式: {@code {baseUrl}/notifications/confirm/{confirmToken}}</p>
 *
 * <p><b>LazyLoad 回避設計</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async} の
 * 組み合わせでは元のトランザクションがすでに終了しているため、{@code REQUIRES_NEW} で
 * 新しいトランザクションを開いてからDBアクセスを行う。これにより {@code LazyInitializationException}
 * を防ぐ。スカラー値を射影する {@code findUserIdAndConfirmTokenByNotificationId} を使い
 * エンティティの遅延ロードを回避する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmableNotificationEmailEventListener {

    private static final String TEMPLATE_NAME = "email/confirmable-notification";

    private final EmailOutboxService emailOutboxService;
    private final MessageSource messageSource;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final TemplateEngine templateEngine;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * 確認通知作成イベントを処理し、各受信者にメールを送信する。
     *
     * @param event 確認通知作成イベント
     */
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleConfirmableNotificationCreated(ConfirmableNotificationCreatedEvent event) {
        log.info("確認通知メール送信開始: confirmableNotificationId={}", event.getConfirmableNotificationId());

        // ユーザーIDとconfirmTokenのペアをスカラー値で取得（LazyLoad回避）
        List<Object[]> tokenRows = recipientRepository
                .findUserIdAndConfirmTokenByNotificationId(event.getConfirmableNotificationId());

        // userId → confirmToken のマップを構築
        Map<Long, String> confirmTokenByUserId = new HashMap<>();
        for (Object[] row : tokenRows) {
            Long userId = ((Number) row[0]).longValue();
            String confirmToken = (String) row[1];
            confirmTokenByUserId.put(userId, confirmToken);
        }

        // ユーザー情報を一括取得
        List<UserEntity> users = userRepository.findByIdIn(event.getRecipientUserIds());

        int sentCount = 0;
        for (UserEntity user : users) {
            String confirmToken = confirmTokenByUserId.get(user.getId());
            if (confirmToken == null) {
                log.warn("confirmTokenが見つかりません: userId={}, notificationId={}",
                        user.getId(), event.getConfirmableNotificationId());
                continue;
            }

            String email = user.getEmail();
            if (email == null || email.isBlank()) {
                log.warn("メールアドレスが未設定のためスキップ: userId={}", user.getId());
                continue;
            }

            try {
                Locale locale = resolveLocale(user);
                String confirmUrl = baseUrl + "/notifications/confirm/" + confirmToken;
                String subject = getMessage("email.confirmableNotification.subject", locale);
                String htmlBody = renderEmailTemplate(confirmUrl, subject, locale);
                emailOutboxService.enqueue(new EmailOutboxRequest(
                        "NOTIFICATION_CONFIRM",
                        locale.toLanguageTag(),
                        email,
                        Map.of("subject", subject, "body", htmlBody),
                        "notification",
                        "notif-confirm:" + event.getConfirmableNotificationId() + ":" + user.getId(),
                        null,
                        user.getId(),
                        null
                ));
                sentCount++;
                log.debug("確認通知メール送信成功: userId={}", user.getId());
            } catch (Exception e) {
                log.error("確認通知メール送信失敗: userId={}, email={}", user.getId(), email, e);
            }
        }

        log.info("確認通知メール送信完了: notificationId={}, total={}, sent={}",
                event.getConfirmableNotificationId(), users.size(), sentCount);
    }

    /**
     * Thymeleaf テンプレートを使ってメール HTML を生成する。
     *
     * @param confirmUrl 確認URL
     * @param subject    件名（テンプレートヘッダー用）
     * @param locale     受信者ロケール
     * @return レンダリング済みHTML文字列
     */
    private String renderEmailTemplate(String confirmUrl, String subject, Locale locale) {
        Context context = new Context(locale);
        context.setVariable("subject", subject);
        context.setVariable("bodyMessage", getMessage("email.confirmableNotification.body", locale));
        context.setVariable("buttonLabel", getMessage("email.confirmableNotification.button", locale));
        context.setVariable("expiryMessage", getMessage("email.confirmableNotification.expiry", locale));
        context.setVariable("ignoreMessage", getMessage("email.confirmableNotification.ignore", locale));
        context.setVariable("footerMessage", getMessage("email.common.footer", locale));
        context.setVariable("confirmUrl", confirmUrl);
        return templateEngine.process(TEMPLATE_NAME, context);
    }

    /**
     * メッセージキーからローカライズされた文字列を取得する。
     * Spring の {@link MessageSource} 経由でプロパティを参照する。
     * F09.18 Phase 18-c で ResourceBundle → MessageSource に置換。
     *
     * @param key    メッセージキー
     * @param locale ロケール
     * @return メッセージ文字列（キーが見つからない場合はキー名をそのまま返す）
     */
    String getMessage(String key, Locale locale) {
        return messageSource.getMessage(key, null, key, locale);
    }

    /**
     * ユーザーのロケール設定に基づいて {@link Locale} を解決する。
     * ロケール情報が未設定の場合は日本語をデフォルトとする。
     *
     * @param user 対象ユーザー
     * @return 解決されたロケール
     */
    private Locale resolveLocale(UserEntity user) {
        try {
            if (user.getLocale() != null && !user.getLocale().isBlank()) {
                return Locale.forLanguageTag(user.getLocale().replace("_", "-"));
            }
        } catch (Exception e) {
            log.debug("ロケール解決失敗。日本語にフォールバック: userId={}", user.getId(), e);
        }
        return Locale.JAPANESE;
    }
}
