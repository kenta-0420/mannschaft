package com.mannschaft.app.search.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 検索サジェストレスポンスDTO。入力補完候補を返す。
 */
@Getter
@RequiredArgsConstructor
public class SearchSuggestionResponse {

    /** サジェストキーワード一覧 */
    private final List<String> suggestions;

    /** 最近の検索履歴からの候補 */
    private final List<String> recentQueries;
}
