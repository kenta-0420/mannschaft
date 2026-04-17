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
    ACCESS_DENIED("SCHEDULE_016", "このスケジュールへのアクセス権がありません", Severity.WARN),

    /** 個人リマインダー上限超過 */
    PERSONAL_REMINDER_LIMIT_EXCEEDED("SCHEDULE_019", "個人スケジュールのリマインダーは最大3件です", Severity.ERROR),

    /** 個人スケジュール上限超過 */
    PERSONAL_SCHEDULE_LIMIT_EXCEEDED("SCHEDULE_020", "個人スケジュールの上限（1000件）に達しています", Severity.WARN),

    /** 一括削除上限超過 */
    BATCH_DELETE_LIMIT_EXCEEDED("SCHEDULE_021", "一括削除は最大50件までです", Severity.ERROR),

    /** スケジュール所有者不一致 */
    NOT_SCHEDULE_OWNER("SCHEDULE_022", "このスケジュールの所有者ではありません", Severity.WARN),

    /** Google Calendar未連携 */
    GOOGLE_CALENDAR_NOT_CONNECTED("SCHEDULE_030", "Google Calendarが連携されていません", Severity.WARN),

    /** Google Calendar連携済み */
    GOOGLE_CALENDAR_ALREADY_CONNECTED("SCHEDULE_031", "Google Calendarは既に連携されています", Severity.WARN),

    /** Google Calendar認証エラー */
    GOOGLE_CALENDAR_AUTH_ERROR("SCHEDULE_032", "Google Calendar認証エラー", Severity.ERROR),

    /** Google Calendar同期失敗 */
    GOOGLE_CALENDAR_SYNC_FAILED("SCHEDULE_033", "Google Calendar同期に失敗しました", Severity.ERROR),

    /** iCalトークン不在 */
    ICAL_TOKEN_NOT_FOUND("SCHEDULE_040", "iCalトークンが見つかりません", Severity.WARN),

    /** iCalトークン無効 */
    ICAL_TOKEN_INACTIVE("SCHEDULE_041", "iCalトークンが無効です", Severity.WARN),

    /** iCalレート制限 */
    ICAL_RATE_LIMITED("SCHEDULE_042", "リクエスト頻度が高すぎます", Severity.WARN),

    /** OAuthステート不一致 */
    OAUTH_STATE_MISMATCH("SCHEDULE_043", "CSRF検証に失敗しました", Severity.ERROR),

    /** OAuthトークン取得失敗 */
    OAUTH_TOKEN_EXCHANGE_FAILED("SCHEDULE_044", "OAuthトークン取得に失敗しました", Severity.ERROR),

    /** 連携TODOとスケジュールのスコープが一致しない */
    TODO_SCOPE_MISMATCH("SCHEDULE_050", "連携TODOとスケジュールのスコープが一致しません", Severity.WARN),

    /** このTODOは既に別のスケジュールと連携されている */
    TODO_ALREADY_LINKED("SCHEDULE_051", "このTODOは既に別のスケジュールと連携されています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
