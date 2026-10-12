package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.MfaEmailOtpPurpose;

/**
 * メールOTP（CMP-261012-0910）の Valkey キー設計。キー名はここで一元管理する。
 *
 * <h2>主体（subject）</h2>
 * <ul>
 *   <li>{@link MfaEmailOtpPurpose#LOGIN}: MFA セッションの sessionHash（{@link MfaSessionService.MfaSession#sessionHash()}）。
 *       セッション A のコードはセッション B で使えない（AC-23）。</li>
 *   <li>{@link MfaEmailOtpPurpose#SETUP} / {@link MfaEmailOtpPurpose#REAUTH}: userId の 10 進文字列（{@link #subjectOf(Long)}）。</li>
 * </ul>
 *
 * <h2>キー一覧（{@code mannschaft:auth:} 配下）</h2>
 * <table>
 *   <tr><th>用途</th><th>キー</th><th>値・TTL</th></tr>
 *   <tr><td>コード（LOGIN）</td><td>{@code mfa_email_otp:{sessionHash}}</td><td>コードのハッシュ・10 分</td></tr>
 *   <tr><td>コード（SETUP）</td><td>{@code mfa_email_setup:{userId}}</td><td>同上</td></tr>
 *   <tr><td>コード（REAUTH）</td><td>{@code mfa_email_reauth:{userId}}</td><td>同上</td></tr>
 *   <tr><td>照合失敗回数（SETUP/REAUTH）</td><td>{@code mfa_email_{purpose}_attempt_count:{subject}}</td>
 *       <td>INCR・コード有効期限と同じ窓（LOGIN は MFA セッションの試行カウンタで数えるため持たない）</td></tr>
 *   <tr><td>再送待ち</td><td>{@code mfa_email_{purpose}_resend_cooldown:{subject}}</td><td>SET NX・60 秒</td></tr>
 *   <tr><td>主体ごとの送信回数</td><td>{@code mfa_email_{purpose}_sent_count:{subject}}</td><td>INCR（LOGIN はセッション 5 回）</td></tr>
 *   <tr><td>ユーザー単位の送信回数</td><td>{@code mfa_email_user_sent_count:{userId}}</td><td>INCR・1 時間（10 回）</td></tr>
 *   <tr><td>IP 単位の送信回数（公開 EP）</td><td>{@code mfa_email_send_ip_rate:{ip}}</td><td>INCR・{@link #IP_SEND_WINDOW_SECONDS} 秒</td></tr>
 * </table>
 *
 * <p><b>命名規約（試練の観測契約）</b>: コード保持キーは {@code mfa_email} を含み、SETUP/REAUTH は {@code :{userId}} で
 * 終わる。レート制限・計数キーは名前に {@code rate / count / limit / resend / cooldown / sent} のいずれかを含めて
 * コード保持キーと区別する。LOGIN のコード保持キー接頭辞 {@code mfa_email_otp:} を他のキーに使わない
 * （「セッション削除と共にコードも消える」の観測が接頭辞一致で行われるため）。</p>
 */
public final class MfaEmailOtpKeys {

    private static final String PREFIX = "mannschaft:auth:";

    /** IP 単位の送信上限（公開 EP {@code /2fa/email/send}。AC-18/AC-20）。 */
    public static final int IP_SEND_LIMIT = 20;
    /** IP 単位の送信計数窓（秒）。 */
    public static final long IP_SEND_WINDOW_SECONDS = 3600L;

    private MfaEmailOtpKeys() {
    }

    /** SETUP / REAUTH の主体文字列。 */
    public static String subjectOf(Long userId) {
        return String.valueOf(userId);
    }

    /** コード保持キー。 */
    public static String codeKey(MfaEmailOtpPurpose purpose, String subject) {
        return switch (purpose) {
            case LOGIN -> PREFIX + "mfa_email_otp:" + subject;
            case SETUP -> PREFIX + "mfa_email_setup:" + subject;
            case REAUTH -> PREFIX + "mfa_email_reauth:" + subject;
        };
    }

    /** 照合失敗回数（SETUP / REAUTH 用。LOGIN は MFA セッションの試行カウンタを使う）。 */
    public static String attemptCountKey(MfaEmailOtpPurpose purpose, String subject) {
        return PREFIX + "mfa_email_" + purposeToken(purpose) + "_attempt_count:" + subject;
    }

    /** 再送待ち（SET NX + TTL 60 秒）。 */
    public static String resendCooldownKey(MfaEmailOtpPurpose purpose, String subject) {
        return PREFIX + "mfa_email_" + purposeToken(purpose) + "_resend_cooldown:" + subject;
    }

    /** 主体ごとの送信回数。 */
    public static String sentCountKey(MfaEmailOtpPurpose purpose, String subject) {
        return PREFIX + "mfa_email_" + purposeToken(purpose) + "_sent_count:" + subject;
    }

    /** ユーザー単位の送信回数（全用途合算）。 */
    public static String userSentCountKey(Long userId) {
        return PREFIX + "mfa_email_user_sent_count:" + userId;
    }

    /** IP 単位の送信回数（公開 EP）。 */
    public static String ipSendRateKey(String ipAddress) {
        return PREFIX + "mfa_email_send_ip_rate:" + ipAddress;
    }

    private static String purposeToken(MfaEmailOtpPurpose purpose) {
        return purpose.name().toLowerCase(java.util.Locale.ROOT);
    }
}
