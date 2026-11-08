package com.mannschaft.app.auth;

/**
 * 二要素認証の主手段（{@code two_factor_auth.method}）。
 *
 * <p>CMP-261012-0910: SMS は保留とし、メールでワンタイムコードを送る方式（{@link #EMAIL}）を追加した。
 * 方式の切替は「無効化 → 再設定」でのみ行う（同時に 2 方式を主手段として持たない）。
 * どちらの方式でもバックアップコード 8 件を持つ。</p>
 */
public enum TwoFactorMethod {

    /** 認証アプリ（RFC 6238 TOTP）。{@code totp_secret} を持つ。 */
    TOTP,

    /** 登録メールアドレスへ 6 桁コードを送る方式。{@code totp_secret} は NULL。 */
    EMAIL
}
