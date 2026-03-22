package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.FeedbackRespondRequest;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.dto.FeedbackStatusRequest;
import com.mannschaft.app.admin.service.FeedbackService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理者向けフィードバック管理コントローラー。
 */
@RestController
@RequestMapping("/api/v1/admin/feedbacks")
@Tag(name = "管理 - フィードバック", description = "F10.1 フィードバック管理API（管理者向け）")
@RequiredArgsConstructor
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * フィードバック一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "フィードバック一覧取得（管理者向け）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FeedbackResponse>> getFeedbacks(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        Page<FeedbackResponse> page = feedbackService.getFeedbacks(scopeType, scopeId, status, pageable);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(page.getContent(), meta));
    }

    /**
     * フィードバックに回答する。
     */
    @PatchMapping("/{id}/respond")
    @Operation(summary = "フィードバック回答")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "回答成功")
    public ResponseEntity<ApiResponse<FeedbackResponse>> respondToFeedback(
            @PathVariable Long id,
            @Valid @RequestBody FeedbackRespondRequest request) {
        FeedbackResponse response = feedbackService.respondToFeedback(id, request, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フィードバックのステータスを変更する。
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "フィードバックステータス変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<FeedbackResponse>> updateFeedbackStatus(
            @PathVariable Long id,
            @Valid @RequestBody FeedbackStatusRequest request) {
        FeedbackResponse response = feedbackService.updateFeedbackStatus(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
