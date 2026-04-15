package com.mannschaft.app.files.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.files.dto.CompleteMultipartRequest;
import com.mannschaft.app.files.dto.CompleteMultipartResponse;
import com.mannschaft.app.files.dto.PartUrlRequest;
import com.mannschaft.app.files.dto.PartUrlResponse;
import com.mannschaft.app.files.dto.StartMultipartUploadRequest;
import com.mannschaft.app.files.dto.StartMultipartUploadResponse;
import com.mannschaft.app.files.service.MultipartUploadService;
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
 * Multipart Upload API コントローラー。
 * 大容量ファイル（100MB 超）の R2 Multipart Upload フローを提供する。
 * 開始・パート URL 発行・完了・中断の4エンドポイントで構成される。
 */
@RestController
@RequestMapping("/api/v1/files/multipart")
@Tag(name = "Multipart Upload", description = "F05.5 大容量ファイル Multipart Upload API")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MultipartUploadController {

    private final MultipartUploadService multipartUploadService;

    /**
     * Multipart Upload を開始する。
     * R2 で Multipart Upload セッションを作成し、uploadId と fileKey を返す。
     */
    @PostMapping("/start")
    @Operation(summary = "Multipart Upload 開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "セッション作成成功")
    public ResponseEntity<ApiResponse<StartMultipartUploadResponse>> startUpload(
            @Valid @RequestBody StartMultipartUploadRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        StartMultipartUploadResponse response = multipartUploadService.startUpload(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * パート用 Presigned URL を発行する。
     * 指定したパート番号に対する Presigned PUT URL を一括発行する。
     * クライアントはこの URL に対して直接 PUT リクエストを送信してパートをアップロードする。
     */
    @PostMapping("/{uploadId}/part-url")
    @Operation(summary = "パート Presigned URL 発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL 発行成功")
    public ResponseEntity<ApiResponse<PartUrlResponse>> getPartUrls(
            @PathVariable String uploadId,
            @Valid @RequestBody PartUrlRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        PartUrlResponse response = multipartUploadService.getPartUrls(uploadId, userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Multipart Upload を完了する。
     * 全パートのアップロード後に呼び出し、R2 にオブジェクトを組み立てる。
     */
    @PostMapping("/{uploadId}/complete")
    @Operation(summary = "Multipart Upload 完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アップロード完了")
    public ResponseEntity<ApiResponse<CompleteMultipartResponse>> completeUpload(
            @PathVariable String uploadId,
            @Valid @RequestBody CompleteMultipartRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        CompleteMultipartResponse response = multipartUploadService.completeUpload(uploadId, userId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Multipart Upload を中断する。
     * タイムアウトやユーザーキャンセル時に呼び出す。アップロード済みパートを破棄する。
     */
    @DeleteMapping("/{uploadId}")
    @Operation(summary = "Multipart Upload 中断")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "中断完了")
    public ResponseEntity<Void> abortUpload(@PathVariable String uploadId) {
        Long userId = SecurityUtils.getCurrentUserId();
        multipartUploadService.abortUpload(uploadId, userId);
        return ResponseEntity.noContent().build();
    }
}
