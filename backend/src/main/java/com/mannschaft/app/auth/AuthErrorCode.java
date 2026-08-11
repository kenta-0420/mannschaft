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

    /** TOTPコード使用済み（409: 状態競合） */
    AUTH_018("AUTH_018", "TOTPコードは既に使用済みです", Severity.WARN),

    /** 2段階認証未有効化 */
    AUTH_019("AUTH_019", "2段階認証がまだ有効化されていません", Severity.WARN),

    /** バックアップコード不正 */
    AUTH_020("AUTH_020", "バックアップコードが正しくありません", Severity.WARN),

    /** バックアップコード全使用済み（409: 状態競合） */
    AUTH_021("AUTH_021", "バックアップコードが全て使用済みです", Severity.WARN),

    /** 2FA回復メール送信上限 */
    AUTH_022("AUTH_022", "2FA回復メール送信回数が超過しています", Severity.WARN),

    /** 2FA回復トークン無効 */
    AUTH_023("AUTH_023", "2FA回復トークンが無効または期限切れです", Severity.WARN),

    /** WebAuthn認証失敗 */
    AUTH_024("AUTH_024", "WebAuthn認証に失敗しました", Severity.WARN),

    /** WebAuthnデバイス重複登録（409: 登録状態との競合） */
    AUTH_025("AUTH_025", "WebAuthnデバイスが既に登録されています", Severity.WARN),

    /** リプレイ攻撃検出 */
    AUTH_026("AUTH_026", "リプレイ攻撃の可能性が検出されました", Severity.WARN),

    /** OAuth認可コード無効 */
    AUTH_027("AUTH_027", "OAuth認可コードが無効です", Severity.WARN),

    /** 未サポートOAuthプロバイダー */
    AUTH_028("AUTH_028", "このOAuthプロバイダーはサポートされていません", Severity.WARN),

    /** OAuthプロバイダー未連携（404: 解除対象の連携が存在しない） */
    AUTH_029("AUTH_029", "OAuthプロバイダーはこのアカウントに連携されていません", Severity.WARN),

    /** OAuth連携解除時ログイン手段喪失（409: 状態競合） */
    AUTH_030("AUTH_030", "OAuthプロバイダーを連携解除するとログイン手段が失われます", Severity.WARN),

    /** OAuth連携トークン無効 */
    AUTH_031("AUTH_031", "OAuth連携トークンが無効または期限切れです", Severity.WARN),

    /** 退会申請不存在（取消可能な状態が無いという状態遷移違反 → 409。自分自身の状態確認のため IDOR ではない） */
    AUTH_032("AUTH_032", "退会申請が存在しません", Severity.WARN),

    /** セッション不存在 */
    AUTH_033("AUTH_033", "セッションが見つかりません", Severity.WARN),

    /** 現在のセッションは無効化不可 */
    AUTH_034("AUTH_034", "現在のセッションは無効化できません", Severity.WARN),

    /** デバイス名バリデーションエラー */
    AUTH_035("AUTH_035", "デバイス名が無効です（1〜100文字、制御文字不可）", Severity.WARN),

    /** 国コードバリデーションエラー */
    AUTH_040("AUTH_040", "国コードが無効です（ISO 3166-1 alpha-2 形式 例: JP・US・DE）", Severity.WARN),

    /** アクセストークン期限切れ（兄弟の AUTH_007/026/039 と同じく401） */
    AUTH_036("AUTH_036", "アクセストークンの有効期限が切れています", Severity.WARN),

    /** アクセストークン不正（署名不一致・フォーマット異常。401） */
    AUTH_037("AUTH_037", "アクセストークンが無効です", Severity.WARN),

    /** アクセストークンがブラックリスト登録済み（個別ログアウト後。401） */
    AUTH_038("AUTH_038", "このセッションは既にログアウトされています", Severity.WARN),

    /** ユーザーの全トークン無効化後のアクセス（全デバイスログアウト後） */
    AUTH_039("AUTH_039", "全デバイスのセッションが無効化されています", Severity.WARN),

    /** 退会処理中のメールアドレスで登録しようとした */
    AUTH_041("AUTH_041", "このメールアドレスは退会処理中のアカウントで使用されています。ログインすると退会を取り消して再利用できます", Severity.WARN),

    /** ベータ期間中は招待コードが必要 */
    AUTH_042("AUTH_042", "ベータ期間中は招待コードが必要です", Severity.WARN),

    /** 招待コードが無効またはベータ対象外 */
    AUTH_043("AUTH_043", "招待コードが無効またはベータ対象外です", Severity.WARN),

    /** レート制限超過（ログイン/登録/パスワードリセット/2FA等の試行回数上限） */
    AUTH_044("AUTH_044", "リクエストが集中しています。しばらく時間をおいて再試行してください", Severity.WARN),

    // ===== F01.9 年齢確認・保護者同意機能 (AUTH_050〜AUTH_070) =====

    /** 生年月日未指定 */
    AUTH_050("AUTH_050", "birth_date is required", Severity.WARN),

    /** 日付フォーマット誤り */
    AUTH_051("AUTH_051", "Invalid date format (expected: YYYY-MM-DD)", Severity.WARN),

    /** 未来日付 */
    AUTH_052("AUTH_052", "Birth date cannot be in the future", Severity.WARN),

    /** 100年以上前の生年月日 */
    AUTH_053("AUTH_053", "Birth date is too far in the past", Severity.WARN),

    /** 保護者同意トークン無効・期限切れ・使用済み統一 */
    AUTH_060("AUTH_060", "Parental consent token is invalid or expired", Severity.WARN),

    /** 自己承認防止 */
    AUTH_062("AUTH_062", "Cannot register yourself as a guardian", Severity.WARN),

    /** 未成年保護者防止 */
    AUTH_063("AUTH_063", "Guardian must be 18 years or older", Severity.WARN),

    /** 子側からの最終リンク削除防止 */
    AUTH_064("AUTH_064", "Cannot remove the last guardian link", Severity.WARN),

    /** 保護者側からの最終リンク解除防止 */
    AUTH_065("AUTH_065", "Please invite another guardian first", Severity.WARN),

    /** 保護者退会ブロック */
    AUTH_066("AUTH_066", "You are the sole guardian of a minor account", Severity.WARN),

    /** 招待数上限 */
    AUTH_067("AUTH_067", "Maximum 3 pending invitations allowed", Severity.WARN),

    /** 重複招待防止 */
    AUTH_068("AUTH_068", "An invitation has already been sent to this email", Severity.WARN),

    /** 自己招待防止 */
    AUTH_069("AUTH_069", "Cannot invite your own email address", Severity.WARN),

    /** PENDING_PARENTAL_CONSENT での操作ブロック */
    AUTH_070("AUTH_070", "This operation is not allowed for accounts pending parental consent", Severity.WARN),

    // ===== F02.10 §391 郵便番号検証基盤（国別レジストリ駆動） =====

    /** 対応国で郵便番号が未入力（必須違反） */
    AUTH_071("AUTH_071", "postal code is required for your region", Severity.WARN),

    /** 郵便番号フォーマット不正 */
    AUTH_072("AUTH_072", "invalid postal code format", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
