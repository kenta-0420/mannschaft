package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timeline.dto.VideoUploadUrlRequest;
import com.mannschaft.app.timeline.dto.VideoUploadUrlResponse;
import com.mannschaft.app.timeline.service.TimelineVideoAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * タイムライン添付ファイル管理コントローラー。
 * 動画ファイルの R2 直アップロード用 Presigned URL 発行 API を提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline/attachments")
@Tag(name = "タイムライン添付ファイル", description = "F04.1 タイムライン動画 Presigned URL")
@RequiredArgsConstructor
public class TimelineAttachmentController {

    private final TimelineVideoAttachmentService videoAttachmentService;

    /**
     * 動画ファイル用 R2 Presigned PUT URL を発行する。
     * 100MB 以下: 本 API で Presigned URL を取得 → R2 に直 PUT
     * 100MB 超: F05.5 の Multipart Upload フローを使用
     */
    @PostMapping("/upload-url")
    @Operation(summary = "動画アップロード Presigned URL 発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL 発行成功")
    public ResponseEntity<ApiResponse<VideoUploadUrlResponse>> getVideoUploadUrl(
            @Valid @RequestBody VideoUploadUrlRequest request) {
        VideoUploadUrlResponse response = videoAttachmentService.generateUploadUrl(
                request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
