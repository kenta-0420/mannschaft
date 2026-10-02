package com.mannschaft.app.workflow.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentPresignRequest;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentPresignResponse;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentRegisterRequest;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentResponse;
import com.mannschaft.app.workflow.dto.WorkflowCommentRequest;
import com.mannschaft.app.workflow.dto.WorkflowCommentResponse;
import com.mannschaft.app.workflow.service.WorkflowCommentService;
import com.mannschaft.app.workflow.service.WorkflowRequestAttachmentService;
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
 * ワークフローコメント・添付ファイルコントローラー。コメントCRUD・添付ファイル参照APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/workflow-requests/{requestId}")
@Tag(name = "ワークフローコメント・添付", description = "F05.6 コメント・添付ファイル管理")
@RequiredArgsConstructor
public class WorkflowCommentController {

    private final WorkflowCommentService commentService;
    private final WorkflowRequestAttachmentService attachmentService;


    /**
     * コメント一覧を取得する。
     */
    @GetMapping("/comments")
    @Operation(summary = "コメント一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WorkflowCommentResponse>>> listComments(
            @PathVariable Long requestId) {
        List<WorkflowCommentResponse> comments =
                commentService.listComments(requestId, SecurityUtils.getCurrentUserId());
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
        WorkflowCommentResponse response = commentService.createComment(requestId, SecurityUtils.getCurrentUserId(), request);
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
        WorkflowCommentResponse response = commentService.updateComment(
                requestId, commentId, SecurityUtils.getCurrentUserId(), request);
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
        commentService.deleteComment(requestId, commentId, SecurityUtils.getCurrentUserId());
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
        List<WorkflowAttachmentResponse> attachments =
                attachmentService.listAttachments(requestId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(attachments));
    }

    /**
     * 添付ファイルアップロード用 Pre-signed URL を発行する（F05.6 Phase 11 第二陣 2-γ）。
     */
    @PostMapping("/upload-url")
    @Operation(summary = "添付ファイル アップロード URL 発行",
            description = "クライアントが返却された uploadUrl に対して PUT で直接アップロードする。完了後 POST /attachments で登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<WorkflowAttachmentPresignResponse>> presignUpload(
            @PathVariable Long requestId,
            @Valid @RequestBody WorkflowAttachmentPresignRequest request) {
        WorkflowAttachmentPresignResponse response = attachmentService.presignUpload(
                requestId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 添付ファイルを登録する（F05.6 Phase 11 第二陣 2-γ）。
     */
    @PostMapping("/attachments")
    @Operation(summary = "添付ファイル登録",
            description = "Pre-signed URL でのアップロード完了後にメタデータを登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<WorkflowAttachmentResponse>> registerAttachment(
            @PathVariable Long requestId,
            @Valid @RequestBody WorkflowAttachmentRegisterRequest request) {
        WorkflowAttachmentResponse response = attachmentService.registerAttachment(
                requestId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 添付ファイルを削除する（F05.6 Phase 11 第二陣 2-γ）。
     */
    @DeleteMapping("/attachments/{attachmentId}")
    @Operation(summary = "添付ファイル削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable Long requestId,
            @PathVariable Long attachmentId) {
        attachmentService.deleteAttachment(requestId, attachmentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
