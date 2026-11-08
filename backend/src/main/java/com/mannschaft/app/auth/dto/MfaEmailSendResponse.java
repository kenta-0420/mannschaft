package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メールOTP 送信の応答（ログイン用・設定用・本人確認用で共通。CMP-261012-0910 AC-19）。
 */
@Getter
@RequiredArgsConstructor
public class MfaEmailSendResponse {

    /** 送信先のマスク済みアドレス（例: {@code t***@example.com}）。 */
    private final String maskedEmail;
    /** コードの有効期限（秒）。 */
    private final long expiresInSeconds;
    /** 次に再送できるまでの秒数。 */
    private final long resendAfterSeconds;
}
