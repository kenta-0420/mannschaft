package com.mannschaft.app.bulletin;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F05.1 掲示板のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum BulletinErrorCode implements ErrorCode {

    /** カテゴリが見つからない */
    CATEGORY_NOT_FOUND("BULLETIN_001", "カテゴリが見つかりません", Severity.WARN),

    /** スレッドが見つからない */
    THREAD_NOT_FOUND("BULLETIN_002", "スレッドが見つかりません", Severity.WARN),

    /** 返信が見つからない */
    REPLY_NOT_FOUND("BULLETIN_003", "返信が見つかりません", Severity.WARN),

    /** スレッドがロックされている */
    THREAD_LOCKED("BULLETIN_004", "このスレッドはロックされています", Severity.WARN),

    /** スレッドがアーカイブされている */
    THREAD_ARCHIVED("BULLETIN_005", "このスレッドはアーカイブされています", Severity.WARN),

    /** 投稿権限不足 */
    INSUFFICIENT_POST_ROLE("BULLETIN_006", "この操作に必要な権限がありません", Severity.WARN),

    /** 添付ファイルが見つからない */
    ATTACHMENT_NOT_FOUND("BULLETIN_007", "添付ファイルが見つかりません", Severity.WARN),

    /** リアクションが見つからない */
    REACTION_NOT_FOUND("BULLETIN_008", "リアクションが見つかりません", Severity.WARN),

    /** リアクション重複 */
    DUPLICATE_REACTION("BULLETIN_009", "既に同じリアクションが存在します", Severity.WARN),

    /** カテゴリ名重複 */
    DUPLICATE_CATEGORY_NAME("BULLETIN_010", "同じスコープ内に同名のカテゴリが存在します", Severity.WARN),

    /** 自身の投稿でない */
    NOT_AUTHOR("BULLETIN_011", "自分の投稿のみ編集できます", Severity.WARN),

    /** 親返信が異なるスレッドに属している */
    PARENT_REPLY_MISMATCH("BULLETIN_012", "親返信が異なるスレッドに属しています", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
