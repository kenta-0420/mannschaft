package com.mannschaft.app.gallery.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.gallery.GalleryErrorCode;
import com.mannschaft.app.gallery.dto.MediaUploadUrlRequest;
import com.mannschaft.app.gallery.dto.MediaUploadUrlResponse;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * ギャラリーメディア（写真・動画）用 Presigned Upload URL 発行サービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GalleryMediaUploadService {

    private static final Duration PHOTO_TTL = Duration.ofMinutes(10);
    private static final Duration VIDEO_TTL = Duration.ofMinutes(15);

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic");
    private static final Set<String> ALLOWED_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime");

    private final R2StorageService r2StorageService;
    private final PhotoAlbumService albumService;

    /**
     * アルバムのスコープ情報と mediaType に応じた R2 Presigned PUT URL を発行する。
     * R2 オブジェクトキー形式:
     *   gallery/{scope_type}/{scope_id}/album-{albumId}/{photo|video}-{uuid}.{ext}
     */
    public MediaUploadUrlResponse generateUploadUrl(Long albumId, MediaUploadUrlRequest request, Long userId) {
        PhotoAlbumEntity album = albumService.findAlbumOrThrow(albumId);
        validateContentType(request.getMediaType(), request.getContentType());

        // teamId/organizationId でスコープを判定
        String scopeType;
        long scopeId;
        if (album.getTeamId() != null) {
            scopeType = "TEAM";
            scopeId = album.getTeamId();
        } else {
            scopeType = "ORGANIZATION";
            scopeId = album.getOrganizationId();
        }

        String ext = resolveExtension(request.getContentType());
        String mediaPrefix = "PHOTO".equals(request.getMediaType()) ? "photo" : "video";
        String uuid = UUID.randomUUID().toString();
        String r2Key = String.format("gallery/%s/%d/album-%d/%s-%s.%s",
                scopeType, scopeId, albumId, mediaPrefix, uuid, ext);

        Duration ttl = "VIDEO".equals(request.getMediaType()) ? VIDEO_TTL : PHOTO_TTL;
        PresignedUploadResult result = r2StorageService.generateUploadUrl(r2Key, request.getContentType(), ttl);
        log.info("ギャラリーメディア Presigned URL 発行: userId={}, albumId={}, type={}, key={}",
                userId, albumId, request.getMediaType(), r2Key);
        return new MediaUploadUrlResponse(result.uploadUrl(), result.s3Key(), ttl.toSeconds());
    }

    private void validateContentType(String mediaType, String contentType) {
        if ("PHOTO".equals(mediaType) && !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException(GalleryErrorCode.UNSUPPORTED_CONTENT_TYPE);
        }
        if ("VIDEO".equals(mediaType) && !ALLOWED_VIDEO_TYPES.contains(contentType)) {
            throw new BusinessException(GalleryErrorCode.UNSUPPORTED_CONTENT_TYPE);
        }
    }

    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/heic" -> "heic";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> "bin";
        };
    }
}
