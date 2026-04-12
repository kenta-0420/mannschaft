package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.timeline.dto.VideoUploadUrlRequest;
import com.mannschaft.app.timeline.dto.VideoUploadUrlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * タイムライン動画ファイル用 Presigned URL 発行サービス。
 * R2 に直アップロードするための Presigned PUT URL を生成する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineVideoAttachmentService {

    private static final Duration UPLOAD_TTL = Duration.ofMinutes(15);
    private static final long UPLOAD_TTL_SECONDS = UPLOAD_TTL.toSeconds();

    private final R2StorageService r2StorageService;

    /**
     * 動画ファイル用 R2 Presigned PUT URL を発行する。
     * R2 オブジェクトキー形式: timeline/{scope_type}/{scope_id}/tmp/{uuid}.{ext}
     *
     * @param request リクエスト（contentType, scopeType, scopeId）
     * @param userId  ログインユーザー ID（ログ用）
     * @return Presigned URL とオブジェクトキー
     */
    public VideoUploadUrlResponse generateUploadUrl(VideoUploadUrlRequest request, Long userId) {
        String ext = resolveExtension(request.getContentType());
        String scopeType = request.getScopeType().toUpperCase();
        long scopeId = request.getScopeId() != null ? request.getScopeId() : 0L;
        String uuid = UUID.randomUUID().toString();
        String r2Key = String.format("timeline/%s/%d/tmp/%s.%s", scopeType, scopeId, uuid, ext);

        PresignedUploadResult result = r2StorageService.generateUploadUrl(r2Key, request.getContentType(), UPLOAD_TTL);
        log.info("動画アップロード Presigned URL 発行: userId={}, key={}", userId, r2Key);
        return new VideoUploadUrlResponse(result.uploadUrl(), result.s3Key(), UPLOAD_TTL_SECONDS);
    }

    /** MIME タイプから拡張子を返す。 */
    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> throw new IllegalArgumentException("非対応 MIME タイプ: " + contentType);
        };
    }
}
