package com.mannschaft.app.matching;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.1 マッチング・対外交流のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum MatchingErrorCode implements ErrorCode {

    /** 募集が見つからない */
    REQUEST_NOT_FOUND("MATCHING_001", "募集が見つかりません", Severity.WARN),

    /** 応募が見つからない */
    PROPOSAL_NOT_FOUND("MATCHING_002", "応募が見つかりません", Severity.WARN),

    /** 募集が編集不可（OPEN以外） */
    REQUEST_NOT_EDITABLE("MATCHING_003", "この募集は編集できません", Severity.WARN),

    /** 自チームの募集に応募不可 */
    SELF_PROPOSAL_NOT_ALLOWED("MATCHING_004", "自チームの募集には応募できません", Severity.WARN),

    /** 既に応募済み */
    DUPLICATE_PROPOSAL("MATCHING_005", "既にこの募集に応募しています", Severity.WARN),

    /** 募集がOPEN以外で応募不可 */
    REQUEST_NOT_OPEN("MATCHING_006", "この募集は応募を受け付けていません", Severity.WARN),

    /** NGチームによる応募ブロック */
    NG_TEAM_BLOCKED("MATCHING_007", "この募集への応募はブロックされています", Severity.WARN),

    /** 募集が既にマッチング成立済み */
    REQUEST_ALREADY_MATCHED("MATCHING_008", "この募集は既にマッチング成立済みです", Severity.WARN),

    /** 応募のステータスが不正 */
    INVALID_PROPOSAL_STATUS("MATCHING_009", "この操作は現在の応募ステータスでは実行できません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("MATCHING_010", "この操作に必要な権限がありません", Severity.WARN),

    /** レビューが見つからない */
    REVIEW_NOT_FOUND("MATCHING_011", "レビューが見つかりません", Severity.WARN),

    /** レビュー重複 */
    DUPLICATE_REVIEW("MATCHING_012", "既にこのマッチングにレビューを投稿しています", Severity.WARN),

    /** レビュー期限超過 */
    REVIEW_PERIOD_EXPIRED("MATCHING_013", "レビュー投稿期限（30日）を過ぎています", Severity.WARN),

    /** レビュー権限なし（関与していないマッチング） */
    REVIEW_NOT_PARTICIPANT("MATCHING_014", "このマッチングに関与していないためレビューできません", Severity.WARN),

    /** NGチームが見つからない */
    NG_TEAM_NOT_FOUND("MATCHING_015", "NGチーム設定が見つかりません", Severity.WARN),

    /** 自チームをNG設定不可 */
    SELF_NG_NOT_ALLOWED("MATCHING_016", "自チームをNG設定することはできません", Severity.WARN),

    /** 既にNG設定済み */
    DUPLICATE_NG_TEAM("MATCHING_017", "既にNGチームに設定されています", Severity.WARN),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("MATCHING_018", "テンプレートが見つかりません", Severity.WARN),

    /** テンプレート数上限 */
    TEMPLATE_LIMIT_EXCEEDED("MATCHING_019", "テンプレート数が上限（20件）に達しています", Severity.WARN),

    /** 都道府県が見つからない */
    PREFECTURE_NOT_FOUND("MATCHING_020", "都道府県が見つかりません", Severity.WARN),

    /** キャンセル種別が不正 */
    INVALID_CANCELLATION_TYPE("MATCHING_021", "この操作は現在のキャンセル種別では実行できません", Severity.WARN),

    /** キャンセル実行者自身は合意承認不可 */
    CANNOT_AGREE_OWN_CANCEL("MATCHING_022", "キャンセルを実行したチームは合意承認できません", Severity.WARN),

    /** 日程候補が多すぎる */
    TOO_MANY_PROPOSED_DATES("MATCHING_023", "日程候補は最大5件です", Severity.WARN),

    /** 募集が成立済みのため取り下げ不可 */
    REQUEST_MATCHED_CANNOT_DELETE("MATCHING_024", "成立済みの募集は取り下げできません。先にキャンセルしてください", Severity.WARN),

    /** 日付範囲バリデーションエラー */
    INVALID_DATE_RANGE("MATCHING_025", "開始日は終了日以前である必要があります", Severity.WARN),

    /** 参加人数バリデーションエラー */
    INVALID_PARTICIPANT_RANGE("MATCHING_026", "最低参加人数は最大参加人数以下である必要があります", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
