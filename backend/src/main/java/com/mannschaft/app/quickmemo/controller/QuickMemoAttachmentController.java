package com.mannschaft.app.quickmemo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.quickmemo.dto.AttachmentSummary;
import com.mannschaft.app.quickmemo.dto.ConfirmUploadRequest;
import com.mannschaft.app.quickmemo.dto.PresignRequest;
import com.mannschaft.app.quickmemo.dto.PresignResponse;
import com.mannschaft.app.quickmemo.service.QuickMemoAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ポイっとメモ添付ファイル コントローラー。
 * Presigned URL 発行・確認・削除を担当する。
 */
@RestController
@RequestMapping("/api/v1/quick-memos/{memoId}/attachments")
@Tag(name = "ポイっとメモ添付ファイル", description = "F02.5 画像添付管理")
@RequiredArgsConstructor
public class QuickMemoAttachmentController {

    private final QuickMemoAttachmentService attachmentService;

    @PostMapping("/presign")
    @Operation(summary = "Presigned URL 発行")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#memoId, authentication)")
    public ResponseEntity<ApiResponse<PresignResponse>> presignUrl(
            @PathVariable Long memoId,
            @Valid @RequestBody PresignRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(attachmentService.presignUrl(memoId, userId, request)));
    }

    @PostMapping("/confirm")
    @Operation(summary = "アップロード確認（マジックバイト検証）")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#memoId, authentication)")
    public ResponseEntity<ApiResponse<AttachmentSummary>> confirmUpload(
            @PathVariable Long memoId,
            @Valid @RequestBody ConfirmUploadRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(attachmentService.confirmUpload(memoId, userId, request)));
    }

    @DeleteMapping("/{attachmentId}")
    @Operation(summary = "添付ファイル削除")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#memoId, authentication)")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable Long memoId,
            @PathVariable Long attachmentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        attachmentService.deleteAttachment(memoId, userId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
