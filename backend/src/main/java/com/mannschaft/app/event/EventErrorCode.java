package com.mannschaft.app.event;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.8 イベント管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum EventErrorCode implements ErrorCode {

    /** イベントが見つからない */
    EVENT_NOT_FOUND("EVENT_001", "イベントが見つかりません", Severity.WARN),

    /** イベントステータス遷移不正 */
    INVALID_STATUS_TRANSITION("EVENT_002", "この操作は現在のイベントステータスでは実行できません", Severity.WARN),

    /** 参加登録受付終了 */
    REGISTRATION_CLOSED("EVENT_003", "参加登録の受付は終了しています", Severity.WARN),

    /** 定員オーバー */
    CAPACITY_FULL("EVENT_004", "定員に達しています", Severity.WARN),

    /** 二重登録 */
    ALREADY_REGISTERED("EVENT_005", "既にこのイベントに登録済みです", Severity.WARN),

    /** チケット使用済み */
    TICKET_ALREADY_USED("EVENT_006", "このチケットは既に使用済みです", Severity.WARN),

    /** 招待トークン不正 */
    INVALID_INVITE_TOKEN("EVENT_007", "招待トークンが無効です", Severity.WARN),

    /** チケット種別上限 */
    MAX_TICKET_TYPES("EVENT_008", "チケット種別の上限に達しています", Severity.ERROR),

    /** タイムテーブル項目上限 */
    MAX_TIMETABLE_ITEMS("EVENT_009", "タイムテーブル項目の上限に達しています", Severity.ERROR),

    /** チケット種別が見つからない */
    TICKET_TYPE_NOT_FOUND("EVENT_010", "チケット種別が見つかりません", Severity.WARN),

    /** 参加登録が見つからない */
    REGISTRATION_NOT_FOUND("EVENT_011", "参加登録が見つかりません", Severity.WARN),

    /** チケットが見つからない */
    TICKET_NOT_FOUND("EVENT_012", "チケットが見つかりません", Severity.WARN),

    /** チェックインが見つからない */
    CHECKIN_NOT_FOUND("EVENT_013", "チェックイン記録が見つかりません", Severity.WARN),

    /** タイムテーブル項目が見つからない */
    TIMETABLE_ITEM_NOT_FOUND("EVENT_014", "タイムテーブル項目が見つかりません", Severity.WARN),

    /** 招待トークンが見つからない */
    INVITE_TOKEN_NOT_FOUND("EVENT_015", "招待トークンが見つかりません", Severity.WARN),

    /** スラグ重複 */
    SLUG_ALREADY_EXISTS("EVENT_016", "このスラグは既に使用されています", Severity.WARN),

    /** 参加登録ステータス不正 */
    INVALID_REGISTRATION_STATUS("EVENT_017", "この操作は現在の登録ステータスでは実行できません", Severity.WARN),

    /** チケットステータス不正 */
    INVALID_TICKET_STATUS("EVENT_018", "この操作は現在のチケットステータスでは実行できません", Severity.WARN),

    /** 招待トークン使用回数上限 */
    INVITE_TOKEN_EXHAUSTED("EVENT_019", "招待トークンの使用回数上限に達しています", Severity.WARN),

    /** チケット種別の発行数上限 */
    TICKET_TYPE_SOLD_OUT("EVENT_020", "このチケット種別は完売しています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
