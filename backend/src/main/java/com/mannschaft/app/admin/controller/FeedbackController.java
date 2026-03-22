package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.CreateFeedbackRequest;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.service.FeedbackService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一般ユーザー向けフィードバック（目安箱）コントローラー。
 */
@RestController
@RequestMapping("/api/v1/feedbacks")
@Tag(name = "フィードバック", description = "F10.1 フィードバック投稿・投票API")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * フィードバックを投稿する。
     */
    @PostMapping
    @Operation(summary = "フィードバック投稿")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "投稿成功")
    public ResponseEntity<ApiResponse<FeedbackResponse>> createFeedback(
            @Valid @RequestBody CreateFeedbackRequest request) {
        FeedbackResponse response = feedbackService.createFeedback(request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 自分のフィードバック一覧を取得する。
     */
    @GetMapping("/me")
    @Operation(summary = "自分のフィードバック一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FeedbackResponse>> getMyFeedbacks(Pageable pageable) {
        Page<FeedbackResponse> page = feedbackService.getMyFeedbacks(getCurrentUserId(), pageable);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(page.getContent(), meta));
    }

    /**
     * フィードバックに投票する。
     */
    @PostMapping("/{id}/votes")
    @Operation(summary = "フィードバック投票")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "投票成功")
    public ResponseEntity<Void> vote(@PathVariable Long id) {
        feedbackService.vote(id, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * フィードバックの投票を取り消す。
     */
    @DeleteMapping("/{id}/votes")
    @Operation(summary = "フィードバック投票取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取消成功")
    public ResponseEntity<Void> unvote(@PathVariable Long id) {
        feedbackService.unvote(id, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
