package com.mannschaft.app.activity.controller;

import com.mannschaft.app.activity.dto.ActivityCommentResponse;
import com.mannschaft.app.activity.dto.CreateCommentRequest;
import com.mannschaft.app.activity.dto.UpdateCommentRequest;
import com.mannschaft.app.activity.service.ActivityCommentService;
import com.mannschaft.app.common.ApiResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 活動コメントコントローラー。コメントのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/activities/{activityId}/comments")
@Tag(name = "活動コメント", description = "F06.4 活動記録コメントCRUD")
@RequiredArgsConstructor
public class ActivityCommentController {

    private final ActivityCommentService commentService;


    /**
     * コメント一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "コメント一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ActivityCommentResponse>>> listComments(
            @PathVariable Long activityId) {
        return ResponseEntity.ok(ApiResponse.of(commentService.listComments(activityId)));
    }

    /**
     * コメントを投稿する。
     */
    @PostMapping
    @Operation(summary = "コメント投稿")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "投稿成功")
    public ResponseEntity<ApiResponse<ActivityCommentResponse>> createComment(
            @PathVariable Long activityId,
            @Valid @RequestBody CreateCommentRequest request) {
        ActivityCommentResponse response = commentService.createComment(activityId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * コメントを編集する。
     */
    @PutMapping("/{commentId}")
    @Operation(summary = "コメント編集")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "編集成功")
    public ResponseEntity<ApiResponse<ActivityCommentResponse>> updateComment(
            @PathVariable Long activityId,
            @PathVariable Long commentId,
            @Valid @RequestBody UpdateCommentRequest request) {
        return ResponseEntity.ok(ApiResponse.of(commentService.updateComment(commentId, SecurityUtils.getCurrentUserId(), request)));
    }

    /**
     * コメントを削除する。
     */
    @DeleteMapping("/{commentId}")
    @Operation(summary = "コメント削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long activityId,
            @PathVariable Long commentId) {
        commentService.deleteComment(commentId);
        return ResponseEntity.noContent().build();
    }
}
