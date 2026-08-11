package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.schedule.dto.ScheduleCommentResponse;
import com.mannschaft.app.schedule.dto.CreateScheduleCommentRequest;
import com.mannschaft.app.schedule.dto.MentionCandidateResponse;
import com.mannschaft.app.schedule.dto.ThreadMetaResponse;
import com.mannschaft.app.schedule.dto.ThreadSettingsRequest;
import com.mannschaft.app.schedule.dto.ThreadSettingsResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleCommentRequest;
import com.mannschaft.app.schedule.service.ScheduleCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.16 予定コメントスレッド API（設計書 §4.1）。
 *
 * <p>各エンドポイントは {@link ScheduleCommentService} 経由で必ず
 * {@code ScheduleCommentAccessGuard} を通す（§4.5.2・ArchUnit 番人
 * {@code ScheduleCommentAuthzGuardArchTest} は Controller→Service→Guard の深さ2 到達を検査する）。</p>
 */
@RestController
@RequestMapping("/api/v1/schedules/{scheduleId}/comments")
@Tag(name = "予定コメント", description = "F03.16 予定コメントスレッド")
@RequiredArgsConstructor
public class ScheduleCommentController {

    private final ScheduleCommentService commentService;

    @GetMapping
    @Operation(summary = "コメント一覧")
    public ResponseEntity<PagedResponse<ScheduleCommentResponse>> listComments(
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        // 【裁定A・2026-08-12】全8エンドポイントが認証必須。ScheduleVisibility に公開値が無く
        // min_view_role は絞る方向にしか働かないため、一覧 GET のみ未認証を許す特例は撤回した。
        Long viewerId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(commentService.listComments(scheduleId, page, size, sort, viewerId));
    }

    @GetMapping("/meta")
    @Operation(summary = "スレッド状態")
    public ResponseEntity<ApiResponse<ThreadMetaResponse>> getMeta(@PathVariable Long scheduleId) {
        Long viewerId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(commentService.getMeta(scheduleId, viewerId)));
    }

    @GetMapping("/{commentId}/replies")
    @Operation(summary = "返信一覧")
    public ResponseEntity<PagedResponse<ScheduleCommentResponse>> listReplies(
            @PathVariable Long scheduleId,
            @PathVariable String commentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Long viewerId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(commentService.listReplies(scheduleId, commentId, page, size, sort, viewerId));
    }

    @GetMapping("/mention-candidates")
    @Operation(summary = "メンション候補")
    public ResponseEntity<ApiResponse<List<MentionCandidateResponse>>> mentionCandidates(
            @PathVariable Long scheduleId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int size) {
        Long viewerId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(commentService.mentionCandidates(scheduleId, q, size, viewerId)));
    }

    @PostMapping
    @Operation(summary = "コメント投稿")
    public ResponseEntity<ApiResponse<ScheduleCommentResponse>> createComment(
            @PathVariable Long scheduleId,
            @RequestBody CreateScheduleCommentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScheduleCommentResponse response = commentService.createComment(scheduleId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PatchMapping("/{commentId}")
    @Operation(summary = "コメント編集")
    public ResponseEntity<ApiResponse<ScheduleCommentResponse>> updateComment(
            @PathVariable Long scheduleId,
            @PathVariable String commentId,
            @RequestBody UpdateScheduleCommentRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(commentService.updateComment(scheduleId, commentId, userId, request)));
    }

    @DeleteMapping("/{commentId}")
    @Operation(summary = "コメント削除")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long scheduleId,
            @PathVariable String commentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        commentService.deleteComment(scheduleId, commentId, userId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/settings")
    @Operation(summary = "スレッド開閉")
    public ResponseEntity<ApiResponse<ThreadSettingsResponse>> updateSettings(
            @PathVariable Long scheduleId,
            @RequestBody ThreadSettingsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(commentService.updateSettings(scheduleId, userId, request)));
    }
}
