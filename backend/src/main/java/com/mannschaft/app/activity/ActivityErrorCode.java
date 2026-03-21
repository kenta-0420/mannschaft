package com.mannschaft.app.activity;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F06.4 活動記録のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ActivityErrorCode implements ErrorCode {

    /** 活動記録が見つからない */
    ACTIVITY_NOT_FOUND("ACTIVITY_001", "活動記録が見つかりません", Severity.WARN),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("ACTIVITY_002", "テンプレートが見つかりません", Severity.WARN),

    /** コメントが見つからない */
    COMMENT_NOT_FOUND("ACTIVITY_004", "コメントが見つかりません", Severity.WARN),

    /** テンプレート数上限超過 */
    TEMPLATE_LIMIT_EXCEEDED("ACTIVITY_005", "テンプレート数の上限（20件）に到達しました", Severity.WARN),

    /** フィールド数上限超過 */
    FIELD_LIMIT_EXCEEDED("ACTIVITY_006", "フィールド数の上限（15個）を超えています", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("ACTIVITY_007", "この操作に必要な権限がありません", Severity.WARN),

    /** 自身の投稿でない */
    NOT_AUTHOR("ACTIVITY_008", "自分の投稿のみ編集できます", Severity.WARN),

    /** スケジュールから既に活動記録が生成済み */
    DUPLICATE_SCHEDULE_ACTIVITY("ACTIVITY_009", "指定スケジュールから既に活動記録が生成されています", Severity.WARN),

    /** 参加者が見つからない */
    PARTICIPANT_NOT_FOUND("ACTIVITY_010", "参加者が見つかりません", Severity.WARN),

    /** 参加者が重複 */
    DUPLICATE_PARTICIPANT("ACTIVITY_011", "既に参加登録されています", Severity.WARN),

    /** 必須フィールド未入力 */
    REQUIRED_FIELD_MISSING("ACTIVITY_012", "必須フィールドが入力されていません", Severity.WARN),

    /** フィールド型不一致 */
    FIELD_TYPE_MISMATCH("ACTIVITY_013", "フィールドの型が一致しません", Severity.WARN),

    /** テンプレートIDの変更は不可 */
    TEMPLATE_CHANGE_NOT_ALLOWED("ACTIVITY_014", "テンプレートの変更はできません", Severity.WARN),

    /** プリセットが見つからない */
    PRESET_NOT_FOUND("ACTIVITY_016", "プリセットテンプレートが見つかりません", Severity.WARN),

    /** 最低1名の参加者が必要 */
    MINIMUM_PARTICIPANT_REQUIRED("ACTIVITY_017", "最低1名の参加者が必要です", Severity.WARN),

    /** フィールド型の変更は禁止 */
    FIELD_TYPE_CHANGE_NOT_ALLOWED("ACTIVITY_018", "フィールド型の変更はできません", Severity.WARN),

    /** フィールドキーのリネームは禁止 */
    FIELD_KEY_RENAME_NOT_ALLOWED("ACTIVITY_019", "フィールドキーの変更はできません", Severity.WARN),

    /** 終了時刻が開始時刻より前 */
    INVALID_TIME_RANGE("ACTIVITY_020", "終了時刻は開始時刻より後に設定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
