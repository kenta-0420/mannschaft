package com.mannschaft.app.auth;

/**
 * メールOTP の用途（CMP-261012-0910）。用途ごとに Valkey キーの名前空間を分け、
 * ある用途で発行したコードを別用途で使えないようにする（AC-23/AC-51）。
 */
public enum MfaEmailOtpPurpose {
    /** ログイン時の 2FA 検証（MFA セッションに紐付く）。 */
    LOGIN,
    /** メール 2FA の有効化（設定用）。 */
    SETUP,
    /** 無効化・バックアップコード再生成時の本人確認（認証済み・本人固定）。 */
    REAUTH
}
