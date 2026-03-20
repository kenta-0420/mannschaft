package com.mannschaft.app.schedule;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.1 スケジュール・出欠管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ScheduleErrorCode implements ErrorCode {

    /** スケジュールが見つからない */
    SCHEDULE_NOT_FOUND("SCHEDULE_001", "スケジュールが見つかりません", Severity.WARN),

    /** 開始日時と終了日時の整合性エラー */
    INVALID_DATE_RANGE("SCHEDULE_002", "開始日時は終了日時より前である必要があります", Severity.ERROR),

    /** 出欠管理対象外のスケジュール */
    ATTENDANCE_NOT_REQUIRED("SCHEDULE_003", "このスケジュールは出欠管理対象外です", Severity.WARN),

    /** 出欠回答期限超過 */
    ATTENDANCE_DEADLINE_PASSED("SCHEDULE_004", "出欠回答期限を過ぎています", Severity.WARN),

    /** 既にキャンセル済み */
    SCHEDULE_ALREADY_CANCELLED("SCHEDULE_005", "スケジュールは既にキャンセルされています", Severity.WARN),

    /** 既に完了済み */
    SCHEDULE_ALREADY_COMPLETED("SCHEDULE_006", "スケジュールは既に完了しています", Severity.WARN),

    /** アンケート設問数上限超過 */
    MAX_SURVEYS_EXCEEDED("SCHEDULE_007", "アンケート設問は最大10件です", Severity.ERROR),

    /** リマインダー数上限超過 */
    MAX_REMINDERS_EXCEEDED("SCHEDULE_008", "リマインダーは最大5件です", Severity.ERROR),

    /** 同一招待先への重複招待 */
    CROSS_INVITE_ALREADY_EXISTS("SCHEDULE_009", "同じ招待先への招待が既に存在します", Severity.WARN),

    /** 招待が見つからない */
    CROSS_INVITE_NOT_FOUND("SCHEDULE_010", "招待が見つかりません", Severity.WARN),

    /** 招待状態不正 */
    CROSS_INVITE_INVALID_STATUS("SCHEDULE_011", "この操作は現在の招待状態では実行できません", Severity.WARN),

    /** 繰り返しルール不正 */
    INVALID_RECURRENCE_RULE("SCHEDULE_012", "繰り返しルールが不正です", Severity.ERROR),

    /** アンケート設問が見つからない */
    SURVEY_NOT_FOUND("SCHEDULE_013", "アンケート設問が見つかりません", Severity.WARN),

    /** コメント必須エラー */
    COMMENT_REQUIRED("SCHEDULE_014", "コメントは必須です", Severity.ERROR),

    /** スコープ不正 */
    INVALID_SCOPE("SCHEDULE_015", "スケジュールのスコープが不正です", Severity.ERROR),

    /** アクセス権なし */
    ACCESS_DENIED("SCHEDULE_016", "このスケジュールへのアクセス権がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
