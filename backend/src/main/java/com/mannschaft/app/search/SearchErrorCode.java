package com.mannschaft.app.search;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.6 グローバル検索のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum SearchErrorCode implements ErrorCode {

    /** 検索クエリが空 */
    EMPTY_QUERY("SEARCH_001", "検索クエリを入力してください", Severity.WARN),

    /** 検索クエリが長すぎる */
    QUERY_TOO_LONG("SEARCH_002", "検索クエリは100文字以内で入力してください", Severity.WARN),

    /** 保存済みクエリが見つからない */
    SAVED_QUERY_NOT_FOUND("SEARCH_003", "保存済み検索クエリが見つかりません", Severity.WARN),

    /** 検索履歴が見つからない */
    HISTORY_NOT_FOUND("SEARCH_004", "検索履歴が見つかりません", Severity.WARN),

    /** 保存済みクエリの上限超過 */
    MAX_SAVED_QUERIES_EXCEEDED("SEARCH_005", "保存済みクエリは最大20件です", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
