package com.mannschaft.app.member;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F06.2 メンバー紹介のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    /** ページが見つからない */
    PAGE_NOT_FOUND("MEMBER_001", "ページが見つかりません", Severity.WARN),

    /** セクションが見つからない */
    SECTION_NOT_FOUND("MEMBER_002", "セクションが見つかりません", Severity.WARN),

    /** プロフィールが見つからない */
    PROFILE_NOT_FOUND("MEMBER_003", "メンバープロフィールが見つかりません", Severity.WARN),

    /** フィールド定義が見つからない */
    FIELD_NOT_FOUND("MEMBER_004", "フィールド定義が見つかりません", Severity.WARN),

    /** スラッグ重複 */
    DUPLICATE_SLUG("MEMBER_005", "同じスラッグのページが既に存在します", Severity.WARN),

    /** 年度重複 */
    DUPLICATE_YEAR("MEMBER_006", "同じ年度のページが既に存在します", Severity.WARN),

    /** メインページ重複 */
    DUPLICATE_MAIN_PAGE("MEMBER_007", "メインページは既に存在します", Severity.WARN),

    /** ユーザー重複 */
    DUPLICATE_USER("MEMBER_008", "同一ページに既に登録されているユーザーです", Severity.WARN),

    /** セルフ編集不許可 */
    SELF_EDIT_NOT_ALLOWED("MEMBER_009", "このページではセルフ編集が許可されていません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("MEMBER_010", "この操作に必要な権限がありません", Severity.WARN),

    /** コピー元ページ不正 */
    INVALID_SOURCE_PAGE("MEMBER_011", "コピー元ページが不正です", Severity.WARN),

    /** バリデーションエラー（カスタムフィールド） */
    INVALID_CUSTOM_FIELD("MEMBER_012", "カスタムフィールドの値が不正です", Severity.WARN),

    /** 並び替え件数上限超過 */
    REORDER_LIMIT_EXCEEDED("MEMBER_013", "並び替え件数の上限（100件）を超えています", Severity.WARN),

    /** 一括登録件数上限超過 */
    BULK_LIMIT_EXCEEDED("MEMBER_014", "一括登録の上限（100件）を超えています", Severity.WARN),

    /** プレビュートークン期限切れ */
    PREVIEW_TOKEN_EXPIRED("MEMBER_015", "プレビュートークンが無効または期限切れです", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
