package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.common.EmailTemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * 認証メール送信イベントリスナー。
 * SES v2 経由でメール認証・パスワードリセットメールを送信する。
 * Thymeleaf テンプレートと 6 言語の properties を使い、ロケール別にメール本文を生成する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthEmailEventListener {

    @Value("${mannschaft.email.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    private final EmailService emailService;
    private final EmailTemplateRenderer emailTemplateRenderer;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserRegistered(UserRegisteredEvent event) {
        String verifyUrl = frontendUrl + "/verify-email?token=" + event.getRawToken();
        // ユーザーのロケールが取れない場合は日本語をデフォルトとする
        Locale locale = Locale.JAPANESE;
        String subject = emailTemplateRenderer.resolveMessage("email.verification.subject", locale);
        String htmlBody = emailTemplateRenderer.renderVerificationEmail(event.getDisplayName(), verifyUrl, locale);
        log.info("認証メール送信開始: to={}", event.getEmail());
        emailService.sendEmail(event.getEmail(), subject, htmlBody);
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEmailVerificationResent(EmailVerificationResentEvent event) {
        String verifyUrl = frontendUrl + "/verify-email?token=" + event.getRawToken();
        Locale locale = Locale.JAPANESE;
        String subject = emailTemplateRenderer.resolveMessage("email.verification.subject", locale);
        // 再送時は displayName が取れないため、空文字で代替する
        String htmlBody = emailTemplateRenderer.renderVerificationEmail("", verifyUrl, locale);
        log.info("認証メール再送信開始: to={}", event.getEmail());
        emailService.sendEmail(event.getEmail(), subject, htmlBody);
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePasswordResetRequested(PasswordResetRequestedEvent event) {
        String resetUrl = frontendUrl + "/reset-password?token=" + event.getRawToken();
        Locale locale = Locale.JAPANESE;
        String subject = emailTemplateRenderer.resolveMessage("email.passwordReset.subject", locale);
        String htmlBody = emailTemplateRenderer.renderPasswordResetEmail(resetUrl, locale);
        log.info("パスワードリセットメール送信開始: to={}", event.getEmail());
        emailService.sendEmail(event.getEmail(), subject, htmlBody);
    }
}
