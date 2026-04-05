package com.mannschaft.app.search.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.search.dto.SaveQueryRequest;
import com.mannschaft.app.search.dto.SavedQueryResponse;
import com.mannschaft.app.search.dto.SearchHistoryResponse;
import com.mannschaft.app.search.service.SearchHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 検索履歴・保存済みクエリコントローラー。検索履歴管理および保存済みクエリ管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "検索履歴・保存済みクエリ", description = "F04.6 検索履歴・保存済みクエリ管理")
@RequiredArgsConstructor
public class SearchHistoryController {

    private final SearchHistoryService searchHistoryService;


    /**
     * 検索履歴一覧を取得する。
     */
    @GetMapping("/recent")
    @Operation(summary = "検索履歴一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SearchHistoryResponse>>> listHistory() {
        List<SearchHistoryResponse> histories = searchHistoryService.listHistory(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(histories));
    }

    /**
     * 検索履歴を全削除する。
     */
    @DeleteMapping("/recent")
    @Operation(summary = "検索履歴全削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteAllHistory() {
        searchHistoryService.deleteAllHistory(SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 検索履歴を個別削除する。
     */
    @DeleteMapping("/recent/{historyId}")
    @Operation(summary = "検索履歴個別削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteHistory(@PathVariable Long historyId) {
        searchHistoryService.deleteHistory(SecurityUtils.getCurrentUserId(), historyId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 保存済みクエリ一覧を取得する。
     */
    @GetMapping("/saved")
    @Operation(summary = "保存済みクエリ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SavedQueryResponse>>> listSavedQueries() {
        List<SavedQueryResponse> savedQueries = searchHistoryService.listSavedQueries(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(savedQueries));
    }

    /**
     * 検索クエリを保存する。
     */
    @PostMapping("/saved")
    @Operation(summary = "検索クエリ保存")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "保存成功")
    public ResponseEntity<ApiResponse<SavedQueryResponse>> saveQuery(
            @Valid @RequestBody SaveQueryRequest request) {
        SavedQueryResponse response = searchHistoryService.saveQuery(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
