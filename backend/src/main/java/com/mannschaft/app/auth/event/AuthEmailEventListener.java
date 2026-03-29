package com.mannschaft.app.auth.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 認証メール送信イベントリスナー。
 * ローカル開発環境では認証URLをログに出力する（メール未送信）。
 * 本番環境ではSMTP経由で実際に送信する（要実装）。
 */
@Slf4j
@Component
public class AuthEmailEventListener {

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserRegistered(UserRegisteredEvent event) {
        String verifyUrl = frontendUrl + "/verify-email?token=" + event.getRawToken();
        log.info("========================================");
        log.info("【開発用】メール認証URL");
        log.info("宛先: {}", event.getEmail());
        log.info("URL:  {}", verifyUrl);
        log.info("========================================");
    }

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEmailVerificationResent(EmailVerificationResentEvent event) {
        String verifyUrl = frontendUrl + "/verify-email?token=" + event.getRawToken();
        log.info("========================================");
        log.info("【開発用】メール認証URL（再送）");
        log.info("宛先: {}", event.getEmail());
        log.info("URL:  {}", verifyUrl);
        log.info("========================================");
    }
}
