package com.mannschaft.app.queue.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.dto.CategoryResponse;
import com.mannschaft.app.queue.dto.QueueStatusResponse;
import com.mannschaft.app.queue.service.QueueCategoryService;
import com.mannschaft.app.queue.service.QueueStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 順番待ちステータスコントローラー。リアルタイムのキュー状況と統計APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/queue/status")
@Tag(name = "順番待ちステータス", description = "F03.7 順番待ちリアルタイムステータス・統計")
@RequiredArgsConstructor
public class QueueStatusController {

    private final QueueStatsService statsService;
    private final QueueCategoryService categoryService;

    /**
     * チーム全体のリアルタイムキューステータスを取得する。
     */
    @GetMapping
    @Operation(summary = "リアルタイムキューステータス")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<QueueStatusResponse>>> getQueueStatus(
            @PathVariable Long teamId) {
        List<CategoryResponse> categories = categoryService.listCategories(
                QueueScopeType.TEAM, teamId);
        List<Long> categoryIds = categories.stream()
                .map(CategoryResponse::getId)
                .toList();
        List<QueueStatusResponse> status = statsService.getQueueStatus(categoryIds);
        return ResponseEntity.ok(ApiResponse.of(status));
    }
}
