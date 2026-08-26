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
 *   <li>cause チェーンに「Unable to load credentials」を含む例外 — AWS 認証情報未設定。
 *       これは恒久的な設定ミスであり、何度リトライしても解消しない。
 *       即 DEAD_LETTER 化することで約3時間の無駄なリトライを回避し、即座にアラートする。</li>
 * </ul>
 *
 * <p>一時失敗 (リトライ): それ以外の全例外 ({@code TooManyRequestsException} /
 * ネットワーク系 {@code SdkClientException}("Unable to execute HTTP request" 等) / 5xx 系全般)。
 * 一時的なネットワーク障害は依然としてリトライ対象を維持する。</p>
 */
@Component
public class SesExceptionClassifier {

    /** 認証情報ロード失敗を示すメッセージの部分文字列。恒久的な設定エラーを表す。 */
    private static final String CREDENTIALS_LOAD_FAILURE = "Unable to load credentials";

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
        if (ex instanceof MessageRejectedException
                || ex instanceof SendingPausedException
                || ex instanceof MailFromDomainNotVerifiedException) {
            return true;
        }
        return hasCredentialsLoadFailureInChain(ex);
    }

    /**
     * 例外の cause チェーンを辿り、いずれかに認証情報ロード失敗メッセージが含まれるか判定する。
     *
     * <p>AWS 認証情報未設定時に発生する
     * {@code SdkClientException("Unable to load credentials from any of the providers in the chain ...")}
     * は恒久的な設定ミスであるため、永久失敗として扱う。</p>
     *
     * <p>自己参照 cause（{@code t.getCause() == t}）でループを脱出することで無限ループを防ぐ。</p>
     *
     * @param ex 検査対象の例外
     * @return cause チェーン中に認証情報ロード失敗メッセージが含まれる場合 true
     */
    private boolean hasCredentialsLoadFailureInChain(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(CREDENTIALS_LOAD_FAILURE)) {
                return true;
            }
            Throwable cause = current.getCause();
            // 自己参照 cause による無限ループを防ぐ
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }
}
