package com.mannschaft.app.workflow.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentResponse;
import com.mannschaft.app.workflow.dto.WorkflowCommentRequest;
import com.mannschaft.app.workflow.dto.WorkflowCommentResponse;
import com.mannschaft.app.workflow.service.WorkflowCommentService;
import com.mannschaft.app.workflow.WorkflowMapper;
import com.mannschaft.app.workflow.repository.WorkflowRequestAttachmentRepository;
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

/**
 * ワークフローコメント・添付ファイルコントローラー。コメントCRUD・添付ファイル参照APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/workflow-requests/{requestId}")
@Tag(name = "ワークフローコメント・添付", description = "F05.6 コメント・添付ファイル管理")
@RequiredArgsConstructor
public class WorkflowCommentController {

    private final WorkflowCommentService commentService;
    private final WorkflowRequestAttachmentRepository attachmentRepository;
    private final WorkflowMapper workflowMapper;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * コメント一覧を取得する。
     */
    @GetMapping("/comments")
    @Operation(summary = "コメント一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WorkflowCommentResponse>>> listComments(
            @PathVariable Long requestId) {
        List<WorkflowCommentResponse> comments = commentService.listComments(requestId);
        return ResponseEntity.ok(ApiResponse.of(comments));
    }

    /**
     * コメントを作成する。
     */
    @PostMapping("/comments")
    @Operation(summary = "コメント作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<WorkflowCommentResponse>> createComment(
            @PathVariable Long requestId,
            @Valid @RequestBody WorkflowCommentRequest request) {
        WorkflowCommentResponse response = commentService.createComment(requestId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * コメントを更新する。
     */
    @PutMapping("/comments/{commentId}")
    @Operation(summary = "コメント更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<WorkflowCommentResponse>> updateComment(
            @PathVariable Long requestId,
            @PathVariable Long commentId,
            @Valid @RequestBody WorkflowCommentRequest request) {
        WorkflowCommentResponse response = commentService.updateComment(requestId, commentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * コメントを削除する。
     */
    @DeleteMapping("/comments/{commentId}")
    @Operation(summary = "コメント削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long requestId,
            @PathVariable Long commentId) {
        commentService.deleteComment(requestId, commentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 添付ファイル一覧を取得する。
     */
    @GetMapping("/attachments")
    @Operation(summary = "添付ファイル一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WorkflowAttachmentResponse>>> listAttachments(
            @PathVariable Long requestId) {
        List<WorkflowAttachmentResponse> attachments = workflowMapper.toAttachmentResponseList(
                attachmentRepository.findByRequestIdOrderByCreatedAtAsc(requestId));
        return ResponseEntity.ok(ApiResponse.of(attachments));
    }
}
