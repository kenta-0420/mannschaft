package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.dto.DownloadUrlResponse;
import com.mannschaft.app.chat.dto.UploadUrlRequest;
import com.mannschaft.app.chat.dto.UploadUrlResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.service.ChatAttachmentService;
import com.mannschaft.app.chat.service.ChatChannelService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * チャットファイルアップロードコントローラー。Pre-signed URL発行APIを提供する。
 *
 * <p>F13 Phase 4-β: presign 直前に {@link ChatAttachmentService#checkAttachmentQuota} を実行し、
 * UX ガード 500MB と F13 統合クォータの両方を検証する。</p>
 */
@RestController
@RequestMapping("/api/v1/chat/files")
@Tag(name = "チャットファイル", description = "F04.2 チャットファイルアップロード")
@RequiredArgsConstructor
public class ChatUploadController {

    private final StorageService storageService;
    private final ChatChannelService chatChannelService;
    private final ChatAttachmentService chatAttachmentService;

    private static final long DEFAULT_EXPIRY_SECONDS = 3600L;

    /**
     * アップロード用 Pre-signed URL を発行する。
     *
     * <p>F13 Phase 4-β: リクエストの {@code channelId} からスコープ（TEAM/ORG/PERSONAL）を解決し、
     * UX ガード 500MB（413） + F13 統合クォータ（409）の両方をチェックしてから署名 URL を発行する。</p>
     */
    @PostMapping("/upload-url")
    @Operation(summary = "アップロードURL発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<UploadUrlResponse>> generateUploadUrl(
            @Valid @RequestBody UploadUrlRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ChatChannelEntity channel = chatChannelService.findChannelOrThrow(request.getChannelId());

        // F13 Phase 4-β: UX ガード 500MB（413） + F13 統合クォータ（409）の事前チェック
        long fileSize = request.getFileSize() != null ? request.getFileSize() : 0L;
        chatAttachmentService.checkAttachmentQuota(channel, fileSize, currentUserId);

        String fileKey = "chat/" + UUID.randomUUID() + "/" + request.getFileName();
        PresignedUploadResult result = storageService.generateUploadUrl(
                fileKey, request.getContentType(), Duration.ofSeconds(DEFAULT_EXPIRY_SECONDS));
        UploadUrlResponse response = new UploadUrlResponse(
                result.uploadUrl(),
                result.s3Key(),
                result.expiresInSeconds()
        );
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ダウンロード用 Pre-signed URL を発行する。
     */
    @GetMapping("/{fileKey}/download-url")
    @Operation(summary = "ダウンロードURL発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<DownloadUrlResponse>> generateDownloadUrl(
            @PathVariable String fileKey) {
        String downloadUrl = storageService.generateDownloadUrl(
                fileKey, Duration.ofSeconds(DEFAULT_EXPIRY_SECONDS));
        DownloadUrlResponse response = new DownloadUrlResponse(
                downloadUrl,
                DEFAULT_EXPIRY_SECONDS
        );
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
