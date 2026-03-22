package com.mannschaft.app.parking;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.3 駐車場区画管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ParkingErrorCode implements ErrorCode {

    /** 区画が見つからない */
    SPACE_NOT_FOUND("PARKING_001", "駐車区画が見つかりません", Severity.WARN),

    /** 車両が見つからない */
    VEHICLE_NOT_FOUND("PARKING_002", "車両が見つかりません", Severity.WARN),

    /** 割り当てが見つからない */
    ASSIGNMENT_NOT_FOUND("PARKING_003", "割り当てが見つかりません", Severity.WARN),

    /** 申請が見つからない */
    APPLICATION_NOT_FOUND("PARKING_004", "申請が見つかりません", Severity.WARN),

    /** 譲渡希望が見つからない */
    LISTING_NOT_FOUND("PARKING_005", "譲渡希望が見つかりません", Severity.WARN),

    /** 来場者予約が見つからない */
    VISITOR_RESERVATION_NOT_FOUND("PARKING_006", "来場者予約が見つかりません", Severity.WARN),

    /** ウォッチリストが見つからない */
    WATCHLIST_NOT_FOUND("PARKING_007", "ウォッチリスト項目が見つかりません", Severity.WARN),

    /** 区画番号が重複 */
    SPACE_NUMBER_DUPLICATE("PARKING_008", "この区画番号は既に使用されています", Severity.WARN),

    /** 区画は既に割り当て済み */
    SPACE_ALREADY_OCCUPIED("PARKING_009", "この区画は既に割り当て済みです", Severity.WARN),

    /** 区画がメンテナンス中 */
    SPACE_IN_MAINTENANCE("PARKING_010", "この区画はメンテナンス中です", Severity.WARN),

    /** 区画が空きでない */
    SPACE_NOT_VACANT("PARKING_011", "この区画は空きではありません", Severity.WARN),

    /** 最大割り当て数超過 */
    MAX_SPACES_EXCEEDED("PARKING_012", "1ユーザーあたりの最大割り当て数を超えています", Severity.WARN),

    /** 申請受付中でない */
    NOT_ACCEPTING_APPLICATIONS("PARKING_013", "この区画は現在申請を受け付けていません", Severity.WARN),

    /** 申請が既に存在 */
    APPLICATION_ALREADY_EXISTS("PARKING_014", "既にこの区画に申請済みです", Severity.WARN),

    /** 申請ステータスが不正 */
    INVALID_APPLICATION_STATUS("PARKING_015", "この申請のステータスでは操作できません", Severity.WARN),

    /** 来場者予約が上限に達した */
    VISITOR_RESERVATION_LIMIT("PARKING_016", "1日あたりの来場者予約上限に達しています", Severity.WARN),

    /** 予約日が許容範囲外 */
    VISITOR_RESERVATION_DATE_OUT_OF_RANGE("PARKING_017", "予約可能な日数の範囲外です", Severity.WARN),

    /** 時間帯が重複 */
    TIME_SLOT_CONFLICT("PARKING_018", "指定された時間帯は既に予約されています", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("PARKING_019", "この操作に必要な権限がありません", Severity.WARN),

    /** スコープ不一致 */
    SCOPE_MISMATCH("PARKING_020", "区画のスコープが一致しません", Severity.WARN),

    /** ナンバープレートが重複 */
    PLATE_NUMBER_DUPLICATE("PARKING_021", "このナンバープレートは既に登録されています", Severity.WARN),

    /** 譲渡希望のステータスが不正 */
    INVALID_LISTING_STATUS("PARKING_022", "この譲渡希望のステータスでは操作できません", Severity.WARN),

    /** 来場者予約のステータスが不正 */
    INVALID_VISITOR_STATUS("PARKING_023", "この来場者予約のステータスでは操作できません", Severity.WARN),

    /** 定期予約テンプレートが見つからない */
    RECURRING_NOT_FOUND("PARKING_024", "定期予約テンプレートが見つかりません", Severity.WARN),

    /** サブリースが見つからない */
    SUBLEASE_NOT_FOUND("PARKING_025", "サブリースが見つかりません", Severity.WARN),

    /** サブリース申請が見つからない */
    SUBLEASE_APPLICATION_NOT_FOUND("PARKING_026", "サブリース申請が見つかりません", Severity.WARN),

    /** サブリースのステータスが不正 */
    INVALID_SUBLEASE_STATUS("PARKING_027", "このサブリースのステータスでは操作できません", Severity.WARN),

    /** サブリース申請のステータスが不正 */
    INVALID_SUBLEASE_APPLICATION_STATUS("PARKING_028", "このサブリース申請のステータスでは操作できません", Severity.WARN),

    /** Stripe Connectが未設定 */
    STRIPE_CONNECT_NOT_CONFIGURED("PARKING_029", "Stripe Connectの設定が完了していません", Severity.WARN),

    /** 一括操作の件数超過 */
    BULK_LIMIT_EXCEEDED("PARKING_030", "一括操作は最大50件までです", Severity.WARN),

    /** 自分の区画でない */
    NOT_OWN_ASSIGNMENT("PARKING_031", "自分に割り当てられた区画ではありません", Severity.WARN),

    /** 区画交換の対象が不正 */
    INVALID_SWAP_TARGET("PARKING_032", "区画交換の対象が不正です", Severity.WARN),

    /** 抽選対象の申請がない */
    NO_LOTTERY_CANDIDATES("PARKING_033", "抽選対象の申請がありません", Severity.WARN),

    /** 時刻指定が30分単位でない */
    INVALID_TIME_SLOT("PARKING_034", "時刻は30分単位で指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
