package com.mannschaft.app.reservation;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.4 予約管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {

    /** 予約ラインが見つからない */
    LINE_NOT_FOUND("RESERVATION_001", "予約ラインが見つかりません", Severity.WARN),

    /** 予約スロットが見つからない */
    SLOT_NOT_FOUND("RESERVATION_002", "予約スロットが見つかりません", Severity.WARN),

    /** 予約が見つからない */
    RESERVATION_NOT_FOUND("RESERVATION_003", "予約が見つかりません", Severity.WARN),

    /** スロットが満席 */
    SLOT_FULL("RESERVATION_004", "このスロットは満席です", Severity.WARN),

    /** スロットがクローズ済み */
    SLOT_CLOSED("RESERVATION_005", "このスロットは受付終了しています", Severity.WARN),

    /** 予約ステータス不正 */
    INVALID_RESERVATION_STATUS("RESERVATION_006", "この操作は現在の予約ステータスでは実行できません", Severity.WARN),

    /** 開始時刻と終了時刻の整合性エラー */
    INVALID_TIME_RANGE("RESERVATION_007", "開始時刻は終了時刻より前である必要があります", Severity.ERROR),

    /** 営業時間外 */
    OUTSIDE_BUSINESS_HOURS("RESERVATION_008", "営業時間外の時刻が指定されています", Severity.WARN),

    /** ブロック時間帯 */
    BLOCKED_TIME_CONFLICT("RESERVATION_009", "ブロックされた時間帯と重複しています", Severity.WARN),

    /** 営業時間が見つからない */
    BUSINESS_HOURS_NOT_FOUND("RESERVATION_010", "営業時間設定が見つかりません", Severity.WARN),

    /** ブロック時間が見つからない */
    BLOCKED_TIME_NOT_FOUND("RESERVATION_011", "ブロック時間が見つかりません", Severity.WARN),

    /** リマインダーが見つからない */
    REMINDER_NOT_FOUND("RESERVATION_012", "リマインダーが見つかりません", Severity.WARN),

    /** 予約重複 */
    DUPLICATE_RESERVATION("RESERVATION_013", "同じスロットに既に予約が存在します", Severity.WARN),

    /** 過去日付への予約 */
    PAST_DATE_RESERVATION("RESERVATION_014", "過去の日付には予約できません", Severity.WARN),

    /** リマインダー上限超過 */
    MAX_REMINDERS_EXCEEDED("RESERVATION_015", "リマインダーは最大3件です", Severity.ERROR),

    /** 臨時休業の日付範囲不正 */
    INVALID_CLOSURE_DATE_RANGE("RESERVATION_016", "終了日は開始日以降の日付を指定してください", Severity.WARN),

    /** 臨時休業が見つからない */
    CLOSURE_NOT_FOUND("RESERVATION_017", "臨時休業が見つかりません", Severity.WARN),

    /** 臨時休業確認レコードが見つからない */
    CLOSURE_CONFIRMATION_NOT_FOUND("RESERVATION_018", "臨時休業確認レコードが見つかりません", Severity.WARN),

    /** 臨時休業の時間帯指定不正 */
    INVALID_CLOSURE_TIME_RANGE("RESERVATION_019", "休業時間帯は開始時刻と終了時刻を両方指定し、開始時刻は終了時刻より前である必要があります（時間単位 / HH:00 のみ）", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
