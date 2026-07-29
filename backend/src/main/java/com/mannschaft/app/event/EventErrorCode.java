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
    MAX_TICKET_TYPES("EVENT_008", "チケット種別の上限に達しています", Severity.WARN),

    /** タイムテーブル項目上限 */
    MAX_TIMETABLE_ITEMS("EVENT_009", "タイムテーブル項目の上限に達しています", Severity.WARN),

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
    TICKET_TYPE_SOLD_OUT("EVENT_020", "このチケット種別は完売しています", Severity.WARN),

    /** 既にRSVP済み */
    ALREADY_RSVPED("EVENT_021", "既にこのイベントにRSVP済みです。更新APIを使用してください", Severity.WARN),

    /** RSVP回答が見つからない */
    RSVP_NOT_FOUND("EVENT_022", "RSVP回答が見つかりません", Severity.WARN),

    /** RSVPモードのイベントではない */
    RSVP_MODE_REQUIRED("EVENT_023", "このイベントはRSVP出欠確認モードではありません", Severity.WARN),

    /** 既に解散通知済み（F03.12 §16） */
    ALREADY_DISMISSED("EVENT_024", "このイベントには既に解散通知が送信されています", Severity.WARN),

    // --- F03.10 代理出席（§4.2 / §5.6 / §5.7） ---

    /** 代理委任が見つからない（404） */
    DELEGATION_NOT_FOUND("EVENT_030", "代理委任が見つかりません", Severity.WARN),

    /** 委任者がスコープのメンバーでない（403） */
    DELEGATION_DELEGATOR_NOT_MEMBER("EVENT_031", "委任者はスコープのメンバーではありません", Severity.WARN),

    /** 代理人がスコープのメンバーでない（422） */
    DELEGATION_DELEGATE_NOT_MEMBER("EVENT_032", "代理人はスコープのメンバーではありません", Severity.WARN),

    /** 自己代理（422） */
    DELEGATION_SELF_DELEGATION("EVENT_033", "自分自身を代理人に指定することはできません", Severity.WARN),

    /** 委任者のアクティブ代理が既に存在する（409） */
    DELEGATION_ALREADY_EXISTS("EVENT_034", "この委任者のアクティブな代理が既に存在します", Severity.WARN),

    /** 連鎖代理禁止違反（422） */
    DELEGATION_CHAINED("EVENT_035", "代理人が既に他者の代理を引き受けているため指定できません（連鎖代理禁止）", Severity.WARN),

    /** 代理出席が許可されていない（422） */
    DELEGATION_NOT_ALLOWED("EVENT_036", "このイベントは代理出席を許可していません", Severity.WARN),

    /** イベントが CANCELLED/COMPLETED（422） */
    DELEGATION_INVALID_EVENT_STATUS("EVENT_037", "キャンセル済み・完了済みのイベントには代理指定できません", Severity.WARN),

    /** 代理人本人でない（403） */
    DELEGATION_NOT_DELEGATE("EVENT_038", "代理人本人のみ承認・拒否できます", Severity.WARN),

    /** ステータスが PENDING でない（422） */
    DELEGATION_NOT_PENDING("EVENT_039", "承認待ち（PENDING）状態の代理のみ承認・拒否できます", Severity.WARN),

    /** 投票セッションが代理連携の事前条件を満たさない（422） */
    DELEGATION_PROXY_VOTE_INVALID("EVENT_040", "指定された投票セッションは代理連携の条件を満たしません", Severity.WARN),

    /** 代理チェックインの delegation が ACCEPTED でない（422） */
    DELEGATION_CHECKIN_NOT_ACCEPTED("EVENT_041", "確定（ACCEPTED）状態の代理のみチェックインできます", Severity.WARN),

    /** 既に代理チェックイン済み（409） */
    DELEGATION_ALREADY_CHECKED_IN("EVENT_042", "この代理は既にチェックイン済みです", Severity.WARN),

    /** 代理チェックインの実行権限なし（403） */
    DELEGATION_CHECKIN_FORBIDDEN("EVENT_043", "代理チェックインを実行する権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
