package com.mannschaft.app.facility;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.5 共用施設予約のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum FacilityErrorCode implements ErrorCode {

    /** 施設が見つからない */
    FACILITY_NOT_FOUND("FACILITY_001", "施設が見つかりません", Severity.WARN),

    /** 施設名が重複 */
    FACILITY_NAME_DUPLICATE("FACILITY_002", "同名の施設が既に存在します", Severity.WARN),

    /** 備品が見つからない */
    EQUIPMENT_NOT_FOUND("FACILITY_003", "備品が見つかりません", Severity.WARN),

    /** 備品名が重複 */
    EQUIPMENT_NAME_DUPLICATE("FACILITY_004", "同名の備品が既に存在します", Severity.WARN),

    /** 利用ルールが見つからない */
    USAGE_RULE_NOT_FOUND("FACILITY_005", "利用ルールが見つかりません", Severity.WARN),

    /** 予約が見つからない */
    BOOKING_NOT_FOUND("FACILITY_006", "予約が見つかりません", Severity.WARN),

    /** 予約ステータス不正 */
    INVALID_BOOKING_STATUS("FACILITY_007", "この操作は現在の予約ステータスでは実行できません", Severity.WARN),

    /** 時間帯重複 */
    TIME_SLOT_CONFLICT("FACILITY_008", "指定時間帯に既存の予約が存在します", Severity.WARN),

    /** 予約上限超過（月間） */
    MONTHLY_BOOKING_LIMIT_EXCEEDED("FACILITY_009", "月間予約上限に達しています", Severity.WARN),

    /** 予約上限超過（日次） */
    DAILY_BOOKING_LIMIT_EXCEEDED("FACILITY_010", "1日の予約上限に達しています", Severity.WARN),

    /** 利用可能時間外 */
    OUTSIDE_AVAILABLE_HOURS("FACILITY_011", "利用可能時間外です", Severity.WARN),

    /** 最小予約時間未満 */
    BELOW_MINIMUM_HOURS("FACILITY_012", "最小予約時間を下回っています", Severity.WARN),

    /** 最大予約時間超過 */
    EXCEED_MAXIMUM_HOURS("FACILITY_013", "最大予約時間を超えています", Severity.WARN),

    /** 予約不可日 */
    BLACKOUT_DATE("FACILITY_014", "予約不可日です", Severity.WARN),

    /** 事前予約時間不足 */
    INSUFFICIENT_ADVANCE_TIME("FACILITY_015", "事前予約の最低時間を満たしていません", Severity.WARN),

    /** 最大予約可能日数超過 */
    EXCEED_MAX_ADVANCE_DAYS("FACILITY_016", "最大予約可能日数を超えています", Severity.WARN),

    /** 支払いが見つからない */
    PAYMENT_NOT_FOUND("FACILITY_017", "支払い情報が見つかりません", Severity.WARN),

    /** 設定が見つからない */
    SETTINGS_NOT_FOUND("FACILITY_018", "施設予約設定が見つかりません", Severity.WARN),

    /** 施設が無効 */
    FACILITY_INACTIVE("FACILITY_019", "この施設は現在利用できません", Severity.WARN),

    /** キャンセル期限超過 */
    CANCELLATION_DEADLINE_PASSED("FACILITY_020", "キャンセル期限を過ぎています", Severity.WARN),

    /** 過去日付への予約 */
    PAST_DATE_BOOKING("FACILITY_021", "過去の日付には予約できません", Severity.WARN),

    /** 利用不可曜日 */
    UNAVAILABLE_DAY_OF_WEEK("FACILITY_022", "この曜日は利用できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
