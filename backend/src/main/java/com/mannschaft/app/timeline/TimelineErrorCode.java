package com.mannschaft.app.timeline;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.1 タイムライン機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum TimelineErrorCode implements ErrorCode {

    /** 投稿が見つからない */
    POST_NOT_FOUND("TIMELINE_001", "投稿が見つかりません", Severity.WARN),

    /** 投稿の所有者ではない */
    NOT_POST_OWNER("TIMELINE_002", "この投稿の所有者ではありません", Severity.WARN),

    /** 投稿が既に削除済み */
    POST_ALREADY_DELETED("TIMELINE_003", "投稿は既に削除されています", Severity.WARN),

    /** 添付ファイル数上限超過 */
    MAX_ATTACHMENTS_EXCEEDED("TIMELINE_004", "添付ファイルは最大10件です", Severity.ERROR),

    /** 投稿コンテンツが空 */
    EMPTY_POST_CONTENT("TIMELINE_005", "投稿内容を入力してください", Severity.ERROR),

    /** リアクション重複 */
    REACTION_ALREADY_EXISTS("TIMELINE_006", "同じリアクションは既に付けています", Severity.WARN),

    /** リアクションが見つからない */
    REACTION_NOT_FOUND("TIMELINE_007", "リアクションが見つかりません", Severity.WARN),

    /** ブックマーク重複 */
    BOOKMARK_ALREADY_EXISTS("TIMELINE_008", "既にブックマーク済みです", Severity.WARN),

    /** ブックマークが見つからない */
    BOOKMARK_NOT_FOUND("TIMELINE_009", "ブックマークが見つかりません", Severity.WARN),

    /** 投票が見つからない */
    POLL_NOT_FOUND("TIMELINE_010", "投票が見つかりません", Severity.WARN),

    /** 投票済み */
    POLL_ALREADY_VOTED("TIMELINE_011", "既に投票済みです", Severity.WARN),

    /** 投票期限切れ */
    POLL_EXPIRED("TIMELINE_012", "投票期限を過ぎています", Severity.WARN),

    /** 投票が終了済み */
    POLL_CLOSED("TIMELINE_013", "投票は終了しています", Severity.WARN),

    /** ミュート重複 */
    MUTE_ALREADY_EXISTS("TIMELINE_014", "既にミュート済みです", Severity.WARN),

    /** ミュートが見つからない */
    MUTE_NOT_FOUND("TIMELINE_015", "ミュートが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
