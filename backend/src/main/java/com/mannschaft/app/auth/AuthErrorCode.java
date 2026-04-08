package com.mannschaft.app.auth;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.1 認証機能のエラーコード定義。
 * ログイン・登録・MFA・OAuth・退会など認証ドメイン全般のエラーを網羅する。
 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    /** メールアドレスまたはパスワードが正しくない */
    AUTH_001("AUTH_001", "メールアドレスまたはパスワードが正しくありません", Severity.WARN),

    /** メール未確認 */
    AUTH_002("AUTH_002", "メールアドレスがまだ確認されていません", Severity.WARN),

    /** アカウントロック */
    AUTH_003("AUTH_003", "アカウントがロックされています。30分後にお試しください", Severity.WARN),

    /** メールアドレス重複 */
    AUTH_004("AUTH_004", "メールアドレスは既に登録されています", Severity.WARN),

    /** 確認メールトークン無効 */
    AUTH_005("AUTH_005", "確認メールのトークンが無効または期限切れです", Severity.WARN),

    /** 確認メール再送信クールダウン */
    AUTH_006("AUTH_006", "確認メール再送信のクールダウン中です。60秒後にお試しください", Severity.WARN),

    /** リフレッシュトークン無効 */
    AUTH_007("AUTH_007", "リフレッシュトークンが無効またはリボーク済みです", Severity.WARN),

    /** パスワードポリシー違反 */
    AUTH_008("AUTH_008", "パスワードがポリシーに準拠していません", Severity.WARN),

    /** 新旧パスワード同一 */
    AUTH_009("AUTH_009", "新しいパスワードは現在のパスワードと異なる必要があります", Severity.WARN),

    /** 現在のパスワード不一致 */
    AUTH_010("AUTH_010", "現在のパスワードが正しくありません", Severity.WARN),

    /** パスワード未設定 */
    AUTH_011("AUTH_011", "パスワードがまだ設定されていません", Severity.WARN),

    /** メールアドレス変更トークン無効 */
    AUTH_012("AUTH_012", "メールアドレス変更のトークンが無効または期限切れです", Severity.WARN),

    /** メールアドレス他ユーザー使用中 */
    AUTH_013("AUTH_013", "メールアドレスは既に他のユーザーに使用されています", Severity.WARN),

    /** メールアドレス変更レートリミット */
    AUTH_014("AUTH_014", "メールアドレス変更のレートリミットに達しています", Severity.WARN),

    /** パスワードリセットトークン無効 */
    AUTH_015("AUTH_015", "パスワードリセットのトークンが無効または期限切れです", Severity.WARN),

    /** TOTPセットアップ失敗 */
    AUTH_016("AUTH_016", "TOTPセットアップに失敗しました", Severity.ERROR),

    /** TOTPコード不正 */
    AUTH_017("AUTH_017", "TOTPコードが正しくありません", Severity.WARN),

    /** TOTPコード使用済み */
    AUTH_018("AUTH_018", "TOTPコードは既に使用済みです", Severity.WARN),

    /** 2段階認証未有効化 */
    AUTH_019("AUTH_019", "2段階認証がまだ有効化されていません", Severity.WARN),

    /** バックアップコード不正 */
    AUTH_020("AUTH_020", "バックアップコードが正しくありません", Severity.WARN),

    /** バックアップコード全使用済み */
    AUTH_021("AUTH_021", "バックアップコードが全て使用済みです", Severity.WARN),

    /** 2FA回復メール送信上限 */
    AUTH_022("AUTH_022", "2FA回復メール送信回数が超過しています", Severity.WARN),

    /** 2FA回復トークン無効 */
    AUTH_023("AUTH_023", "2FA回復トークンが無効または期限切れです", Severity.WARN),

    /** WebAuthn認証失敗 */
    AUTH_024("AUTH_024", "WebAuthn認証に失敗しました", Severity.WARN),

    /** WebAuthnデバイス重複登録 */
    AUTH_025("AUTH_025", "WebAuthnデバイスが既に登録されています", Severity.WARN),

    /** リプレイ攻撃検出 */
    AUTH_026("AUTH_026", "リプレイ攻撃の可能性が検出されました", Severity.WARN),

    /** OAuth認可コード無効 */
    AUTH_027("AUTH_027", "OAuth認可コードが無効です", Severity.WARN),

    /** 未サポートOAuthプロバイダー */
    AUTH_028("AUTH_028", "このOAuthプロバイダーはサポートされていません", Severity.WARN),

    /** OAuthプロバイダー未連携 */
    AUTH_029("AUTH_029", "OAuthプロバイダーはこのアカウントに連携されていません", Severity.WARN),

    /** OAuth連携解除時ログイン手段喪失 */
    AUTH_030("AUTH_030", "OAuthプロバイダーを連携解除するとログイン手段が失われます", Severity.WARN),

    /** OAuth連携トークン無効 */
    AUTH_031("AUTH_031", "OAuth連携トークンが無効または期限切れです", Severity.WARN),

    /** 退会申請不存在 */
    AUTH_032("AUTH_032", "退会申請が存在しません", Severity.WARN),

    /** セッション不存在 */
    AUTH_033("AUTH_033", "セッションが見つかりません", Severity.WARN),

    /** 現在のセッションは無効化不可 */
    AUTH_034("AUTH_034", "現在のセッションは無効化できません", Severity.WARN),

    /** デバイス名バリデーションエラー */
    AUTH_035("AUTH_035", "デバイス名が無効です（1〜100文字、制御文字不可）", Severity.WARN),

    /** アクセストークンの有効期限切れ */
    AUTH_036("AUTH_036", "アクセストークンの有効期限が切れています", Severity.WARN),

    /** アクセストークンが無効（署名不正・形式不正など） */
    AUTH_037("AUTH_037", "アクセストークンが無効です", Severity.WARN),

    /** アクセストークンが無効化されている（個別ログアウト・全デバイスログアウト済み） */
    AUTH_038("AUTH_038", "アクセストークンは既に無効化されています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
