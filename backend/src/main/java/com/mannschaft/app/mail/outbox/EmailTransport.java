package com.mannschaft.app.mail.outbox;

/**
 * F09.18: メール送信トランスポート抽象。
 *
 * <p>実 SES 送信（local 以外）とログのみ送信（local/test）を切替える。
 * {@code mannschaft.email.simulate=true} のとき {@link LoggingEmailTransport} が有効になり、
 * {@code false} または未設定のとき {@link SesEmailTransport} が有効になる。
 * SQS リスナー側の {@code @ConditionalOnProperty} と対称的な設計。</p>
 */
public interface EmailTransport {

    /**
     * メール1通を送信する。
     *
     * @param toAddress 宛先メールアドレス
     * @param subject   件名
     * @param htmlBody  HTML 本文
     * @return SES MessageId（simulate 時は疑似値 "SIMULATED-{UUID}"）。
     *         送信失敗時は実装が例外を投げ、呼び出し側（EmailOutboxServiceImpl）が
     *         SesExceptionClassifier で永久/一時を判定する。
     */
    String send(String toAddress, String subject, String htmlBody);
}
