package com.mannschaft.app.proxyvote;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.3 議決権行使・委任状のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ProxyVoteErrorCode implements ErrorCode {

    /** セッションが見つからない */
    SESSION_NOT_FOUND("PROXY_VOTE_001", "投票セッションが見つかりません", Severity.WARN),

    /** 議案が見つからない */
    MOTION_NOT_FOUND("PROXY_VOTE_002", "議案が見つかりません", Severity.WARN),

    /** コメントが見つからない */
    COMMENT_NOT_FOUND("PROXY_VOTE_003", "コメントが見つかりません", Severity.WARN),

    /** 添付ファイルが見つからない */
    ATTACHMENT_NOT_FOUND("PROXY_VOTE_004", "添付ファイルが見つかりません", Severity.WARN),

    /** 委任状が見つからない */
    DELEGATION_NOT_FOUND("PROXY_VOTE_005", "委任状が見つかりません", Severity.WARN),

    /** 投票レコードが見つからない */
    VOTE_NOT_FOUND("PROXY_VOTE_006", "投票レコードが見つかりません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("PROXY_VOTE_010", "この操作に必要な権限がありません", Severity.WARN),

    /** スコープへのアクセス権限がない */
    SCOPE_ACCESS_DENIED("PROXY_VOTE_011", "スコープへのアクセス権限がありません", Severity.WARN),

    /** ADMIN / DEPUTY_ADMIN (MANAGE_VOTES) ではない */
    MANAGE_VOTES_REQUIRED("PROXY_VOTE_012", "投票管理権限が必要です", Severity.WARN),

    /** 投票者本人でない */
    NOT_VOTE_OWNER("PROXY_VOTE_013", "自分の投票のみ変更できます", Severity.WARN),

    /** 委任者本人でない */
    NOT_DELEGATOR("PROXY_VOTE_014", "委任者本人のみ操作できます", Severity.WARN),

    /** コメント投稿者本人でもADMINでもない */
    NOT_COMMENT_OWNER("PROXY_VOTE_015", "コメントの削除権限がありません", Severity.WARN),

    /** ステータス不正: DRAFT のみ */
    STATUS_MUST_BE_DRAFT("PROXY_VOTE_020", "DRAFT ステータスでのみ実行可能です", Severity.WARN),

    /** ステータス不正: OPEN のみ */
    STATUS_MUST_BE_OPEN("PROXY_VOTE_021", "OPEN ステータスでのみ実行可能です", Severity.WARN),

    /** ステータス不正: CLOSED のみ */
    STATUS_MUST_BE_CLOSED("PROXY_VOTE_022", "CLOSED ステータスでのみ実行可能です", Severity.WARN),

    /** ステータス不正: CLOSED / FINALIZED のみ */
    STATUS_MUST_BE_CLOSED_OR_FINALIZED("PROXY_VOTE_023", "CLOSED または FINALIZED ステータスでのみ実行可能です", Severity.WARN),

    /** ステータス不正: FINALIZED のみ */
    STATUS_MUST_BE_FINALIZED("PROXY_VOTE_024", "FINALIZED ステータスでのみ実行可能です", Severity.WARN),

    /** ステータス不正: 更新不可 */
    SESSION_NOT_UPDATABLE("PROXY_VOTE_025", "CLOSED / FINALIZED のセッションは更新できません", Severity.WARN),

    /** 議案が0件 */
    NO_MOTIONS("PROXY_VOTE_030", "議案が1件以上必要です", Severity.WARN),

    /** 議案数上限超過 */
    MOTION_LIMIT_EXCEEDED("PROXY_VOTE_031", "議案数の上限（30件）に到達しました", Severity.WARN),

    /** OPEN 中に変更不可フィールド */
    FIELD_NOT_UPDATABLE_WHEN_OPEN("PROXY_VOTE_032", "受付開始後は変更できないフィールドです", Severity.WARN),

    /** scope_type とスコープ ID の不整合 */
    SCOPE_ID_MISMATCH("PROXY_VOTE_033", "scope_type とスコープ ID が整合しません", Severity.WARN),

    /** MEETING モードでは meeting_date 必須 */
    MEETING_DATE_REQUIRED("PROXY_VOTE_034", "MEETING モードでは meeting_date が必須です", Severity.WARN),

    /** quorum_threshold の範囲外 */
    INVALID_QUORUM_THRESHOLD("PROXY_VOTE_035", "quorum_threshold は 0.01〜100.00 の範囲で指定してください", Severity.WARN),

    /** WRITTEN モードのセッションでは実行不可 */
    MEETING_MODE_ONLY("PROXY_VOTE_040", "MEETING モードのセッションでのみ実行可能です", Severity.WARN),

    /** 議案が PENDING でない */
    MOTION_NOT_PENDING("PROXY_VOTE_041", "議案が PENDING 状態ではありません", Severity.WARN),

    /** 議案が VOTING でない */
    MOTION_NOT_VOTING("PROXY_VOTE_042", "議案が VOTING 状態ではありません", Severity.WARN),

    /** PENDING の議案が0件 */
    NO_PENDING_MOTIONS("PROXY_VOTE_043", "PENDING 状態の議案がありません", Severity.WARN),

    /** MEETING モードで VOTED でない議案が存在 */
    NOT_ALL_MOTIONS_VOTED("PROXY_VOTE_044", "全議案が VOTED 状態になるまで CLOSE できません", Severity.WARN),

    /** WRITTEN モードで全議案への投票が揃っていない */
    INCOMPLETE_VOTES("PROXY_VOTE_050", "全議案への投票が必要です", Severity.WARN),

    /** VOTING 状態でない議案が含まれている */
    NON_VOTING_MOTION_INCLUDED("PROXY_VOTE_051", "VOTING 状態でない議案が含まれています", Severity.WARN),

    /** 既に投票済み */
    ALREADY_VOTED("PROXY_VOTE_052", "既に投票済みです。変更は PUT /cast を使用してください", Severity.WARN),

    /** 投票権がない */
    NO_VOTING_RIGHT("PROXY_VOTE_053", "投票権がありません", Severity.WARN),

    /** MEETING モードで OPEN 中は投票不可（委任のみ受付） */
    MEETING_VOTE_NOT_ALLOWED("PROXY_VOTE_054", "MEETING モードでは議案の投票開始を待ってください", Severity.WARN),

    /** 自分自身への委任 */
    SELF_DELEGATION("PROXY_VOTE_060", "自分自身への委任はできません", Severity.WARN),

    /** 代理人がスコープ外 */
    DELEGATE_OUT_OF_SCOPE("PROXY_VOTE_061", "代理人がスコープ外のメンバーです", Severity.WARN),

    /** 無記名セッションでは委任不可 */
    DELEGATION_NOT_ALLOWED_ANONYMOUS("PROXY_VOTE_062", "無記名投票セッションでは委任できません", Severity.WARN),

    /** 既に投票済みのため委任不可 */
    ALREADY_VOTED_CANNOT_DELEGATE("PROXY_VOTE_063", "既に投票済みのため委任できません", Severity.WARN),

    /** 既に委任済み */
    ALREADY_DELEGATED("PROXY_VOTE_064", "既に委任状を提出済みです", Severity.WARN),

    /** 委任状のステータスが SUBMITTED でない */
    DELEGATION_NOT_SUBMITTED("PROXY_VOTE_065", "SUBMITTED 状態の委任状のみ承認/却下できます", Severity.WARN),

    /** 委任状のステータスが REJECTED / CANCELLED */
    DELEGATION_ALREADY_RESOLVED("PROXY_VOTE_066", "REJECTED / CANCELLED の委任状は操作できません", Severity.WARN),

    /** 委任受付期限切れ */
    DELEGATION_DEADLINE_PASSED("PROXY_VOTE_067", "委任受付期限を過ぎています", Severity.WARN),

    /** 添付ファイル数上限超過 */
    ATTACHMENT_LIMIT_EXCEEDED("PROXY_VOTE_070", "添付ファイル数の上限に到達しました", Severity.WARN),

    /** ファイルサイズ超過 */
    FILE_SIZE_EXCEEDED("PROXY_VOTE_071", "ファイルサイズが上限を超えています", Severity.WARN),

    /** 対応していないファイル形式 */
    UNSUPPORTED_FILE_TYPE("PROXY_VOTE_072", "対応していないファイル形式です", Severity.WARN),

    /** 議案添付で MINUTES は不可 */
    MINUTES_SESSION_ONLY("PROXY_VOTE_073", "議事録（MINUTES）はセッション添付のみ指定できます", Severity.WARN),

    /** アップロード不可のステータス */
    UPLOAD_NOT_ALLOWED("PROXY_VOTE_074", "現在のステータスではアップロードできません", Severity.WARN),

    /** コメント本文が空 */
    COMMENT_BODY_EMPTY("PROXY_VOTE_080", "コメント本文を入力してください", Severity.WARN),

    /** コメント本文が1,000文字超過 */
    COMMENT_BODY_TOO_LONG("PROXY_VOTE_081", "コメントは1,000文字以内で入力してください", Severity.WARN),

    /** 楽観的ロック競合 */
    OPTIMISTIC_LOCK_CONFLICT("PROXY_VOTE_090", "他のユーザーによる更新と競合しました。再読み込みしてください", Severity.WARN),

    /** リマインドのレートリミット超過 */
    REMIND_RATE_LIMITED("PROXY_VOTE_091", "リマインドは1時間に1回まで送信可能です", Severity.WARN),

    /** 未回答者がいない */
    NO_PENDING_MEMBERS("PROXY_VOTE_092", "未回答のメンバーがいません", Severity.WARN),

    /** 定足数未達 */
    QUORUM_NOT_MET("PROXY_VOTE_093", "定足数に達していません", Severity.INFO);

    private final String code;
    private final String message;
    private final Severity severity;
}
