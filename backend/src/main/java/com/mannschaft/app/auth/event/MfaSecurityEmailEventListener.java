package com.mannschaft.app.auth.event;

import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.MfaMethodPolicy;
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
import java.util.UUID;

/**
 * 2FA のセキュリティ通知メール・回復メールを F09.18 メール配信基盤へ enqueue するリスナー（CMP-261012-0910）。
 *
 * <ul>
 *   <li>{@code MFA_SECURITY_NOTICE}: 有効化（{@link MfaEnabledEvent}）・無効化（{@link MfaDisabledEvent}。本人操作・回復）・
 *       ログイン時のバックアップコード使用（{@link MfaBackupCodeUsedEvent}）を本人の登録アドレスへ知らせる（AC-12/AC-37）。
 *       本文に平文のコード・回復トークンを載せない。</li>
 *   <li>{@code MFA_RECOVERY}: 回復メール（{@link MfaRecoveryRequestedEvent}。回復トークン入りリンク。AC-39）。
 *       従来はこのイベントを受けるメールリスナーが無く、回復メールが届かなかった。</li>
 * </ul>
 *
 * <p>既存の認証メール（{@link AuthEmailEventListener}）と同じ作法で、業務 TX のコミット後に非同期で enqueue する
 * （{@code AFTER_COMMIT} + {@code @Async}）。auth の業務 TX から mail ドメインの TX へ入らない（D-3T）。
 * 通知の失敗は本処理（無効化・ログイン等）に影響させない。本文は ja 固定（裁可事項 9）・スルー方式
 * （{@code subject} / {@code body} を payload に詰める）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MfaSecurityEmailEventListener {

    static final String TEMPLATE_SECURITY_NOTICE = "MFA_SECURITY_NOTICE";
    static final String TEMPLATE_RECOVERY = "MFA_RECOVERY";
    private static final String SOURCE_DOMAIN = "auth";
    private static final String LOCALE = "ja";
    private static final String NOTICE_FOOTER =
            "<p>この操作に心当たりが無い場合は、第三者がアカウントにアクセスしている可能性があります。"
                    + "直ちにパスワードを変更し、2段階認証の設定を確認してください。</p>";

    @Value("${app.base-url}")
    private String baseUrl;

    private final EmailOutboxService emailOutboxService;
    private final UserRepository userRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "2FA の有効化・無効化・バックアップコード使用の本人通知であり、止めると乗っ取りの兆候を本人が知る手段が失われる。認証は CORE でありゲートされない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMfaEnabled(MfaEnabledEvent event) {
        String body = "<p>2段階認証が有効になりました（方式: " + methodLabel(event.getMethod()) + "）。</p>"
                + NOTICE_FOOTER;
        enqueueNotice(event.getUserId(), "enabled", "【Mannschaft】2段階認証が有効になりました", body);
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "2FA の有効化・無効化・バックアップコード使用の本人通知であり、止めると乗っ取りの兆候を本人が知る手段が失われる。認証は CORE でありゲートされない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMfaDisabled(MfaDisabledEvent event) {
        String cause = MfaDisabledEvent.REASON_RECOVERY.equals(event.getReason())
                ? "回復メールの手続きにより、2段階認証が無効になりました。"
                : "アカウント設定から、2段階認証が無効になりました。";
        String body = "<p>" + cause + "</p>"
                + "<p>現在、ログイン時の2段階認証は求められません。必要に応じて設定画面から再度有効にしてください。</p>"
                + NOTICE_FOOTER;
        enqueueNotice(event.getUserId(), "disabled", "【Mannschaft】2段階認証が無効になりました", body);
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "2FA の有効化・無効化・バックアップコード使用の本人通知であり、止めると乗っ取りの兆候を本人が知る手段が失われる。認証は CORE でありゲートされない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMfaBackupCodeUsed(MfaBackupCodeUsedEvent event) {
        String body = "<p>バックアップコードを使ってログインしました。使用したコードは再利用できません。</p>"
                + "<p>残りのバックアップコード: " + event.getBackupCodesRemaining() + " 件</p>"
                + "<p>残りが少ない場合は、設定画面からバックアップコードを再生成してください。</p>"
                + NOTICE_FOOTER;
        enqueueNotice(event.getUserId(), "backup-code-used",
                "【Mannschaft】バックアップコードでログインしました", body);
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "2FA 回復メールの送信であり、止めると端末を失った利用者がログインできなくなる。認証は CORE でありゲートされない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMfaRecoveryRequested(MfaRecoveryRequestedEvent event) {
        if (!MfaMethodPolicy.isEmailDeliverable(event.getEmail())) {
            log.warn("2FA 回復メールを送れる宛先が無いため enqueue しない: userId={}", event.getUserId());
            return;
        }
        String recoveryUrl = baseUrl + "/2fa-recovery?token=" + event.getRawToken();
        String body = "<p>2段階認証の回復が要求されました。</p>"
                + "<p>以下のリンクを開いて手続きを完了すると、2段階認証が無効になりログインできます"
                + "（有効期限 1 時間・1 回限り）。</p>"
                + "<p><a href=\"" + recoveryUrl + "\">" + recoveryUrl + "</a></p>"
                + "<p>この要求に心当たりが無い場合は、このメールを破棄し、直ちにパスワードを変更してください。</p>";
        emailOutboxService.enqueue(new EmailOutboxRequest(
                TEMPLATE_RECOVERY,
                LOCALE,
                event.getEmail(),
                Map.of("subject", "【Mannschaft】2段階認証の回復手続き", "body", body),
                SOURCE_DOMAIN,
                "mfa-recovery:" + event.getUserId() + ":" + UUID.randomUUID(),
                null,
                event.getUserId(),
                null
        ));
        log.info("2FA 回復メール enqueue 完了: userId={}", event.getUserId());
    }

    private void enqueueNotice(Long userId, String kind, String subject, String body) {
        String email = userRepository.findById(userId).map(UserEntity::getEmail).orElse(null);
        if (!MfaMethodPolicy.isEmailDeliverable(email)) {
            log.warn("2FA セキュリティ通知を送れる宛先が無いため enqueue しない: userId={}, kind={}", userId, kind);
            return;
        }
        emailOutboxService.enqueue(new EmailOutboxRequest(
                TEMPLATE_SECURITY_NOTICE,
                LOCALE,
                email,
                Map.of("subject", subject, "body", body),
                SOURCE_DOMAIN,
                "mfa-notice:" + kind + ":" + userId + ":" + UUID.randomUUID(),
                null,
                userId,
                null
        ));
        log.info("2FA セキュリティ通知 enqueue 完了: userId={}, kind={}", userId, kind);
    }

    private static String methodLabel(TwoFactorMethod method) {
        return method == TwoFactorMethod.EMAIL ? "メール" : "認証アプリ";
    }
}
