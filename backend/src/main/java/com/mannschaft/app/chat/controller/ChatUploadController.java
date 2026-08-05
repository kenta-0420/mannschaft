package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.dto.DownloadUrlResponse;
import com.mannschaft.app.chat.dto.UploadUrlRequest;
import com.mannschaft.app.chat.dto.UploadUrlResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.service.ChatAttachmentService;
import com.mannschaft.app.chat.service.ChatChannelService;
import com.mannschaft.app.chat.service.ChatMessageService;
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
    private final ChatMessageService chatMessageService;

    private static final long DEFAULT_EXPIRY_SECONDS = 3600L;

    /**
     * アップロード用 Pre-signed URL を発行する。
     *
     * <p>本エンドポイントは、リクエストの {@code channelId} が指すチャンネルへ
     * <b>呼出ユーザーが投稿してよいこと</b>を、本文投稿と同一の判定
     * （{@link ChatMessageService#checkChannelPostAccess(ChatChannelEntity, Long)}）で保証してから署名 URL を発行する。
     * 署名 URL の書き込み先はチャンネルのスコープ（TEAM / ORGANIZATION / PERSONAL）配下であり、
     * 認可はスコープをリクエストから受け取らずチャンネルエンティティから解決する。</p>
     *
     * <p>F13 Phase 4-β: UX ガード 500MB（413） + F13 統合クォータ（409）も併せて検証する。</p>
     */
    @PostMapping("/upload-url")
    @Operation(summary = "アップロードURL発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<UploadUrlResponse>> generateUploadUrl(
            @Valid @RequestBody UploadUrlRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ChatChannelEntity channel = chatChannelService.findChannelOrThrow(request.getChannelId());

        // 認可: 当該チャンネルへの投稿権限を本文投稿と同一の判定で保証する。
        chatMessageService.checkChannelPostAccess(channel, currentUserId);

        // F13 Phase 4-β: UX ガード 500MB（413） + F13 統合クォータ（409）の事前チェック
        long fileSize = request.getFileSize() != null ? request.getFileSize() : 0L;
        chatAttachmentService.checkAttachmentQuota(channel, fileSize, currentUserId);

        // F13 Phase 5-a: 新統一パス命名規則 "chat/{scopeType}/{scopeId}/{uuid}/{filename}" に変更
        ChatAttachmentService.ScopeResolution scope =
                chatAttachmentService.resolveScope(channel, currentUserId);
        String scopeType = scope.scopeType().name();  // TEAM / ORGANIZATION / PERSONAL
        Long scopeId = scope.scopeId();
        String fileKey = "chat/" + scopeType + "/" + scopeId + "/" + UUID.randomUUID() + "/" + request.getFileName();
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
     *
     * <p>署名 URL は発行された時点でオブジェクト本体への読み取り能力そのものとなるため、
     * 発行前に <b>そのオブジェクトが属するチャンネルを解決し、本文閲覧と同一の判定</b>
     * （{@link ChatMessageService#checkAttachmentDownloadAccess}）を適用する。
     * チャットが管理していないキーは fail-closed で拒否する。</p>
     */
    @GetMapping("/{fileKey}/download-url")
    @Operation(summary = "ダウンロードURL発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "閲覧権限なし")
    public ResponseEntity<ApiResponse<DownloadUrlResponse>> generateDownloadUrl(
            @PathVariable String fileKey) {
        chatMessageService.checkAttachmentDownloadAccess(fileKey, SecurityUtils.getCurrentUserId());
        String downloadUrl = storageService.generateDownloadUrl(
                fileKey, Duration.ofSeconds(DEFAULT_EXPIRY_SECONDS));
        DownloadUrlResponse response = new DownloadUrlResponse(
                downloadUrl,
                DEFAULT_EXPIRY_SECONDS
        );
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
