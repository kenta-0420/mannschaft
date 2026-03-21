package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.filesharing.dto.CommentResponse;
import com.mannschaft.app.filesharing.dto.CreateCommentRequest;
import com.mannschaft.app.filesharing.dto.UpdateCommentRequest;
import com.mannschaft.app.filesharing.service.SharedFileCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ファイルコメントコントローラー。ファイルに対するコメントCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/files/{fileId}/comments")
@Tag(name = "ファイル共有 - コメント", description = "F05.5 ファイルコメント管理")
@RequiredArgsConstructor
public class FileCommentController {

    private final SharedFileCommentService commentService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * ファイルのコメント一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "コメント一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CommentResponse>>> listComments(
            @PathVariable Long fileId) {
        List<CommentResponse> response = commentService.listComments(fileId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * コメントを作成する。
     */
    @PostMapping
    @Operation(summary = "コメント作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CommentResponse>> createComment(
            @PathVariable Long fileId,
            @Valid @RequestBody CreateCommentRequest request) {
        CommentResponse response = commentService.createComment(fileId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * コメントを更新する。
     */
    @PatchMapping("/{commentId}")
    @Operation(summary = "コメント更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CommentResponse>> updateComment(
            @PathVariable Long fileId,
            @PathVariable Long commentId,
            @Valid @RequestBody UpdateCommentRequest request) {
        CommentResponse response = commentService.updateComment(commentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * コメントを削除する。
     */
    @DeleteMapping("/{commentId}")
    @Operation(summary = "コメント削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long fileId,
            @PathVariable Long commentId) {
        commentService.deleteComment(commentId);
        return ResponseEntity.noContent().build();
    }
}
