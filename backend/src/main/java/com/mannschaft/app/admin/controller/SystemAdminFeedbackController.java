package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.FeedbackRespondRequest;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.dto.FeedbackStatusRequest;
import com.mannschaft.app.admin.service.FeedbackService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * システム管理者向けプラットフォーム全体の目安箱管理コントローラー（F10.1）。
 * GENERAL スコープ（scopeId IS NULL）のフィードバックのみ扱う。
 */
@RestController
@RequestMapping("/api/v1/system-admin/feedbacks")
@Tag(name = "システム管理 - 目安箱", description = "F10.1 目安箱管理（システム管理者向け）")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminFeedbackController {

    private final FeedbackService feedbackService;

    @GetMapping
    @Operation(summary = "目安箱一覧取得（GENERAL スコープ・scopeId IS NULL 全件）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FeedbackResponse>> getFeedbacks(
            @RequestParam(required = false) String status,
            Pageable pageable) {
        Page<FeedbackResponse> page = feedbackService.getGeneralFeedbacks(status, pageable);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(page.getContent(), meta));
    }

    @PatchMapping("/{id}/respond")
    @Operation(summary = "目安箱に回答する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "回答成功")
    public ResponseEntity<ApiResponse<FeedbackResponse>> respondToFeedback(
            @PathVariable Long id,
            @Valid @RequestBody FeedbackRespondRequest request) {
        FeedbackResponse response = feedbackService.respondToFeedback(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "目安箱のステータスを変更する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<FeedbackResponse>> updateFeedbackStatus(
            @PathVariable Long id,
            @Valid @RequestBody FeedbackStatusRequest request) {
        FeedbackResponse response = feedbackService.updateFeedbackStatus(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
