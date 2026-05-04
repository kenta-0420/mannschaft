package com.mannschaft.app.cms;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F06.1 CMS・ブログのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum CmsErrorCode implements ErrorCode {

    /** 記事が見つからない */
    POST_NOT_FOUND("CMS_001", "記事が見つかりません", Severity.WARN),

    /** タグが見つからない */
    TAG_NOT_FOUND("CMS_002", "タグが見つかりません", Severity.WARN),

    /** シリーズが見つからない */
    SERIES_NOT_FOUND("CMS_003", "シリーズが見つかりません", Severity.WARN),

    /** リビジョンが見つからない */
    REVISION_NOT_FOUND("CMS_004", "リビジョンが見つかりません", Severity.WARN),

    /** slug重複 */
    DUPLICATE_SLUG("CMS_005", "同じスラッグが既に存在します", Severity.WARN),

    /** タグ名重複 */
    DUPLICATE_TAG_NAME("CMS_006", "同じスコープ内に同名のタグが存在します", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("CMS_007", "この操作に必要な権限がありません", Severity.WARN),

    /** 不正なステータス遷移 */
    INVALID_STATUS_TRANSITION("CMS_008", "このステータス遷移は許可されていません", Severity.WARN),

    /** 楽観的ロック競合 */
    VERSION_CONFLICT("CMS_009", "他のユーザーにより更新されています。ページを再読み込みしてください", Severity.WARN),

    /** 記事が既に公開済み（プレビュー不要） */
    ALREADY_PUBLISHED("CMS_010", "記事は既に公開されています", Severity.WARN),

    /** プレビュートークン無効 */
    PREVIEW_TOKEN_EXPIRED("CMS_011", "プレビュートークンが無効または期限切れです", Severity.WARN),

    /** ソーシャルプロフィール記事の共有不可 */
    SOCIAL_PROFILE_SHARE_NOT_ALLOWED("CMS_012", "ソーシャルプロフィール名義の記事は共有できません", Severity.WARN),

    /** 共有先への重複共有 */
    DUPLICATE_SHARE("CMS_013", "同一チーム/組織に既に共有済みです", Severity.WARN),

    /** 却下理由なし */
    REJECTION_REASON_REQUIRED("CMS_014", "却下理由を入力してください", Severity.WARN),

    /** タグ数上限超過 */
    TAG_LIMIT_EXCEEDED("CMS_015", "1記事あたりのタグ数上限（10個）を超えています", Severity.WARN),

    /** 一括操作上限超過 */
    BULK_LIMIT_EXCEEDED("CMS_016", "一括操作の上限（50件）を超えています", Severity.WARN),

    /** 本文サイズ超過 */
    BODY_SIZE_EXCEEDED("CMS_017", "本文は50,000文字以内で入力してください", Severity.WARN),

    /** 自身の投稿でない */
    NOT_AUTHOR("CMS_018", "自分の投稿のみ編集できます", Severity.WARN),

    /** 共有が見つからない */
    SHARE_NOT_FOUND("CMS_019", "共有が見つかりません", Severity.WARN),

    /** ブログ設定が見つからない */
    SETTINGS_NOT_FOUND("CMS_020", "ブログ設定が見つかりません", Severity.WARN),

    /** リアクション（みたよ！）が既に存在する */
    REACTION_ALREADY_EXISTS("CMS_021", "既にみたよ！を送信済みです", Severity.WARN),

    /** リアクション（みたよ！）が見つからない */
    REACTION_NOT_FOUND("CMS_022", "みたよ！が見つかりません", Severity.WARN),

    /** ストレージクォータ超過 */
    MEDIA_QUOTA_EXCEEDED("CMS_023", "ストレージ容量が不足しているためアップロードできません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
