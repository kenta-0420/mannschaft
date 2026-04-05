package com.mannschaft.app.search.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.search.dto.SearchResultResponse;
import com.mannschaft.app.search.dto.SearchSuggestionResponse;
import com.mannschaft.app.search.service.GlobalSearchService;
import com.mannschaft.app.search.service.SearchHistoryService;
import com.mannschaft.app.search.service.SearchSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * グローバル検索コントローラー。横断検索およびサジェストAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "グローバル検索", description = "F04.6 横断検索・サジェスト")
@RequiredArgsConstructor
public class GlobalSearchController {

    private final GlobalSearchService globalSearchService;
    private final SearchHistoryService searchHistoryService;
    private final SearchSuggestionService searchSuggestionService;


    /**
     * 横断検索を実行する。9種別を横断して検索し、検索履歴を記録する。
     */
    @GetMapping
    @Operation(summary = "横断検索")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "検索成功")
    public ResponseEntity<ApiResponse<SearchResultResponse>> search(
            @RequestParam String q) {
        Long userId = SecurityUtils.getCurrentUserId();
        searchHistoryService.recordHistory(userId, q);
        SearchResultResponse result = globalSearchService.search(q, userId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * 検索サジェストを取得する。入力中のキーワードから候補を返す。
     */
    @GetMapping("/suggestions")
    @Operation(summary = "検索サジェスト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SearchSuggestionResponse>> suggest(
            @RequestParam(defaultValue = "") String q) {
        SearchSuggestionResponse response = searchSuggestionService.suggest(q, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
