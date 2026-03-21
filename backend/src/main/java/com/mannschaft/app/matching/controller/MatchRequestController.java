package com.mannschaft.app.matching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.matching.dto.ActivitySuggestionResponse;
import com.mannschaft.app.matching.dto.CreateMatchRequestRequest;
import com.mannschaft.app.matching.dto.MatchRequestCreateResponse;
import com.mannschaft.app.matching.dto.MatchRequestResponse;
import com.mannschaft.app.matching.service.MatchRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 募集投稿コントローラー。募集のCRUD・検索APIを提供する。
 */
@RestController
@Tag(name = "マッチング募集", description = "F08.1 マッチング募集CRUD・検索")
@RequiredArgsConstructor
public class MatchRequestController {

    private final MatchRequestService requestService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 募集一覧（パブリック検索）。
     */
    @GetMapping("/api/v1/matching/requests")
    @Operation(summary = "募集一覧（パブリック検索）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<MatchRequestResponse>> searchRequests(
            @RequestParam(required = false) String prefectureCode,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String activityType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String visibility,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // TODO: currentTeamIdをセキュリティコンテキストから取得
        Long currentTeamId = getCurrentUserId();

        Page<MatchRequestResponse> result;
        if (keyword != null && !keyword.isBlank()) {
            result = requestService.searchByKeyword(currentTeamId, keyword, PageRequest.of(page, Math.min(size, 50)));
        } else {
            result = requestService.searchRequests(currentTeamId,
                    prefectureCode, cityCode, activityType, category, level, visibility,
                    PageRequest.of(page, Math.min(size, 50)));
        }
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 募集詳細。
     */
    @GetMapping("/api/v1/matching/requests/{id}")
    @Operation(summary = "募集詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<MatchRequestResponse>> getRequest(@PathVariable Long id) {
        Long currentTeamId = getCurrentUserId();
        MatchRequestResponse response = requestService.getRequest(id, currentTeamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 募集内容の編集。
     */
    @PutMapping("/api/v1/matching/requests/{id}")
    @Operation(summary = "募集内容の編集")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MatchRequestResponse>> updateRequest(
            @PathVariable Long id,
            @Valid @RequestBody CreateMatchRequestRequest request) {
        Long currentTeamId = getCurrentUserId();
        MatchRequestResponse response = requestService.updateRequest(id, currentTeamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 募集の取り下げ（論理削除）。
     */
    @DeleteMapping("/api/v1/matching/requests/{id}")
    @Operation(summary = "募集の取り下げ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteRequest(@PathVariable Long id) {
        Long currentTeamId = getCurrentUserId();
        requestService.deleteRequest(id, currentTeamId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 募集投稿の作成。
     */
    @PostMapping("/api/v1/teams/{teamId}/matching/requests")
    @Operation(summary = "募集投稿の作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<MatchRequestCreateResponse>> createRequest(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateMatchRequestRequest request) {
        MatchRequestCreateResponse response = requestService.createRequest(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 自チームの募集一覧。
     */
    @GetMapping("/api/v1/teams/{teamId}/matching/requests")
    @Operation(summary = "自チームの募集一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<MatchRequestResponse>> listTeamRequests(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<MatchRequestResponse> result = requestService.listTeamRequests(teamId, PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * activity_detail のサジェスト。
     */
    @GetMapping("/api/v1/matching/activity-suggestions")
    @Operation(summary = "activity_detail サジェスト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ActivitySuggestionResponse>>> getActivitySuggestions(
            @RequestParam String q,
            @RequestParam(required = false) String activityType) {
        List<ActivitySuggestionResponse> suggestions = requestService.getActivitySuggestions(q, activityType);
        return ResponseEntity.ok(ApiResponse.of(suggestions));
    }
}
