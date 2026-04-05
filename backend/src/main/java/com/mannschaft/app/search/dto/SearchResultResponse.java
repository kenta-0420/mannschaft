package com.mannschaft.app.search.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * グローバル検索統合結果レスポンスDTO。9種別の検索結果を統合して返す。
 */
@Getter
@RequiredArgsConstructor
public class SearchResultResponse {

    /** 検索クエリ */
    private final String query;

    /** 種別ごとの検索結果（キー: schedules, events, reservations, shifts, safetyChecks, queues, teams, organizations, users） */
    private final Map<String, List<Map<String, Object>>> results;

    /** 種別ごとのヒット件数 */
    private final Map<String, Long> counts;

    /** 検索実行時間（ミリ秒） */
    private final long executionTimeMs;
}
