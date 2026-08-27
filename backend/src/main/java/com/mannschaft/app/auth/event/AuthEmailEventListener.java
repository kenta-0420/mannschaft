package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * 認証メール送信イベントリスナー。
 *
 * <p>Phase 18-b 移行: 直接 SES に投げる代わりに F09.18 メール配信基盤
 * ({@link EmailOutboxService#enqueue}) に投入する。テンプレートレンダリングは
 * Worker 側で実施するため、ここでは payload に必要な変数のみを詰める。</p>
 *
 * <p>locale は当面 ja 固定。{@link UserRegisteredEvent} 等にロケールフィールドが
 * 追加されたら切り替える (将来拡張)。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthEmailEventListener {

    @Value("${app.base-url}")
    private String baseUrl;

    private final EmailOutboxService emailOutboxService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。登録・認証メールの送信であり、認証は CORE でありゲートされない。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserRegistered(UserRegisteredEvent event) {
        String verifyUrl = baseUrl + "/verify-email?token=" + event.getRawToken();
        emailOutboxService.enqueue(new EmailOutboxRequest(
                "VERIFICATION",
                "ja",
                event.getEmail(),
                Map.of(
                        "displayName", event.getDisplayName() != null ? event.getDisplayName() : "",
                        "verifyUrl", verifyUrl
                ),
                "auth",
                "register:" + event.getUserId(),
                null,
                event.getUserId(),
                null
        ));
        log.info("認証メール enqueue 完了: to={}, userId={}", event.getEmail(), event.getUserId());
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。登録・認証メールの送信であり、認証は CORE でありゲートされない。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEmailVerificationResent(EmailVerificationResentEvent event) {
        String verifyUrl = baseUrl + "/verify-email?token=" + event.getRawToken();
        // 再送時は displayName が取れないため空文字、ソースイベントは「再送ごとに新規 nonce」で衝突回避
        emailOutboxService.enqueue(new EmailOutboxRequest(
                "VERIFICATION",
                "ja",
                event.getEmail(),
                Map.of(
                        "displayName", "",
                        "verifyUrl", verifyUrl
                ),
                "auth",
                "resent:" + event.getUserId() + ":" + System.nanoTime(),
                null,
                event.getUserId(),
                null
        ));
        log.info("認証メール再送 enqueue 完了: to={}, userId={}", event.getEmail(), event.getUserId());
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。登録・認証メールの送信であり、認証は CORE でありゲートされない。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePasswordResetRequested(PasswordResetRequestedEvent event) {
        String resetUrl = baseUrl + "/reset-password?token=" + event.getRawToken();
        emailOutboxService.enqueue(new EmailOutboxRequest(
                "PASSWORD_RESET",
                "ja",
                event.getEmail(),
                Map.of("resetUrl", resetUrl),
                "auth",
                "pwreset:" + event.getUserId() + ":" + System.nanoTime(),
                null,
                event.getUserId(),
                null
        ));
        log.info("パスワードリセットメール enqueue 完了: to={}, userId={}", event.getEmail(), event.getUserId());
    }
}
