package com.mannschaft.app.auth;

/**
 * 2FA ログイン検証・本人確認で提示する要素の種別（CMP-261012-0910）。
 *
 * <p>{@link TwoFactorMethod} はユーザーが設定した「主手段」であるのに対し、本 enum は
 * 1 回の検証リクエストで「どの要素を出したか」を表す。バックアップコードは主手段にはならないが
 * 検証要素としては使えるため、別 enum とする。</p>
 *
 * <ul>
 *   <li>{@link #TOTP} — 6 桁（{@code ^\d{6}$}）</li>
 *   <li>{@link #BACKUP_CODE} — 8 桁（{@code ^\d{8}$}）</li>
 *   <li>{@link #EMAIL} — 6 桁（{@code ^\d{6}$}）</li>
 * </ul>
 */
public enum MfaVerificationMethod {
    TOTP,
    BACKUP_CODE,
    EMAIL
}
