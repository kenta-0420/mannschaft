package com.mannschaft.app.mail.outbox;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendingPausedException;

/**
 * F09.18 SES 例外の失敗種別分類器 (設計書 §7.2 失敗種別判定)。
 *
 * <p>永久失敗 (即 DEAD_LETTER):</p>
 * <ul>
 *   <li>{@code MessageRejectedException} — バウンスドメイン / 内容拒否</li>
 *   <li>{@code SendingPausedException} — アカウント停止 (v2 SDK では SES v1 の
 *       {@code AccountSendingPausedException} はこちらに統合されている)</li>
 *   <li>{@code MailFromDomainNotVerifiedException} — 送信元ドメイン未検証</li>
 * </ul>
 *
 * <p>一時失敗 (リトライ): それ以外の全例外 ({@code TooManyRequestsException} /
 * {@code SdkClientException} / 5xx 系全般)。</p>
 */
@Component
public class SesExceptionClassifier {

    /**
     * 永久失敗種別かを判定する。
     *
     * @param ex SES 呼び出し時の例外
     * @return true=永久失敗 (即 DEAD_LETTER) / false=一時失敗 (リトライ)
     */
    public boolean isPermanent(Throwable ex) {
        if (ex == null) {
            return false;
        }
        return ex instanceof MessageRejectedException
                || ex instanceof SendingPausedException
                || ex instanceof MailFromDomainNotVerifiedException;
    }
}
