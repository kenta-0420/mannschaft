package com.mannschaft.app.mail.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * F09.18: ログのみ送信トランスポート（local / test 用）。
 *
 * <p>{@code mannschaft.email.simulate=true} のとき有効。
 * 実際のメール送信を行わず、宛先をマスキングしてログ出力し、疑似 messageId を返す。
 * dev 環境で AWS SES 認証情報がなくても DEAD_LETTER が発生しなくなる。</p>
 *
 * <p>SQS リスナー側の {@code @ConditionalOnProperty(name = "mannschaft.ses.sqs.queue-name", matchIfMissing = false)}
 * で local / test はリスナーを起動しない設計と対称的。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mannschaft.email.simulate", havingValue = "true")
public class LoggingEmailTransport implements EmailTransport {

    @Override
    public String send(String toAddress, String subject, String htmlBody) {
        String masked = maskEmail(toAddress);
        log.info("[EMAIL_OUTBOX][SIMULATE] 送信省略 to={} subject={}", masked, subject);
        return "SIMULATED-" + UUID.randomUUID();
    }

    /**
     * メールアドレスのローカル部を先頭1文字以外マスキングする。
     *
     * <p>例: {@code hideharu215@yahoo.co.jp} → {@code h***@yahoo.co.jp}</p>
     *
     * @param email マスキング対象のメールアドレス。null の場合は "null" を返す
     */
    static String maskEmail(String email) {
        if (email == null) {
            return "null";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            // ローカル部が1文字以下、または "@" がない場合はそのまま（マスキング不要）
            return email;
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}
