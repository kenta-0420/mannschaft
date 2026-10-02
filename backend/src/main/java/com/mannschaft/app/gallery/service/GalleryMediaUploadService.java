package com.mannschaft.app.gallery.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.FileTypeValidator;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
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
 *
 * <p><b>F13 Phase 4-δ</b>: Presigned URL 発行前に {@link StorageQuotaService#checkQuota} で
 * クォータを確認する。超過時は {@link GalleryErrorCode#STORAGE_QUOTA_EXCEEDED} をスローする。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GalleryMediaUploadService {

    private static final Duration PHOTO_TTL = Duration.ofMinutes(10);
    private static final Duration VIDEO_TTL = Duration.ofMinutes(15);

    /** {@link FileTypeValidator} の定数を参照する（ローカル定義を廃止）。 */
    private static final Set<String> ALLOWED_IMAGE_TYPES = FileTypeValidator.ALLOWED_IMAGE_TYPES;
    private static final Set<String> ALLOWED_VIDEO_TYPES = FileTypeValidator.ALLOWED_VIDEO_TYPES;

    private final R2StorageService r2StorageService;
    private final PhotoAlbumService albumService;
    /** F13 Phase 4-δ: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;
    /** 認可根治戦役 Wave3-B5: アップロード可否の scope 認可用。 */
    private final AccessControlService accessControlService;

    /**
     * アルバムのスコープ情報と mediaType に応じた R2 Presigned PUT URL を発行する。
     * R2 オブジェクトキー形式:
     *   gallery/{scope_type}/{scope_id}/album-{albumId}/{photo|video}-{uuid}.{ext}
     *
     * <p><b>F13 Phase 4-δ</b>: {@code request.getFileSize()} が non-null の場合、
     * アップロード前にクォータチェックを行う。</p>
     */
    public MediaUploadUrlResponse generateUploadUrl(Long albumId, MediaUploadUrlRequest request, Long userId) {
        PhotoAlbumEntity album = albumService.findAlbumOrThrow(albumId);
        validateContentType(request.getMediaType(), request.getContentType());

        // 認可根治戦役 Wave3-B5: uploadPhotos と同一ポリシー（メンバー必須 かつ
        // ADMIN/DEPUTY_ADMIN または allowMemberUpload=true）。
        Long authzScopeId = PhotoAlbumService.resolveScopeId(album.getTeamId(), album.getOrganizationId());
        String authzScopeType = PhotoAlbumService.resolveScopeType(album.getTeamId());
        accessControlService.checkMembership(userId, authzScopeId, authzScopeType);
        boolean canUpload = accessControlService.isAdminOrAbove(userId, authzScopeId, authzScopeType)
                || Boolean.TRUE.equals(album.getAllowMemberUpload());
        if (!canUpload) {
            throw new BusinessException(GalleryErrorCode.UPLOAD_NOT_ALLOWED);
        }

        // teamId/organizationId でスコープを判定
        StorageScopeType scopeType;
        long scopeId;
        if (album.getTeamId() != null) {
            scopeType = StorageScopeType.TEAM;
            scopeId = album.getTeamId();
        } else {
            scopeType = StorageScopeType.ORGANIZATION;
            scopeId = album.getOrganizationId();
        }

        // F13 Phase 4-δ: 統合クォータチェック（fileSize が提供されている場合のみ）
        if (request.getFileSize() != null) {
            try {
                storageQuotaService.checkQuota(scopeType, scopeId, request.getFileSize());
            } catch (StorageQuotaExceededException e) {
                log.info("ギャラリーメディアのクォータ超過: userId={}, albumId={}, scope={}/{}, requested={}",
                        userId, albumId, scopeType, scopeId, e.getRequestedBytes());
                throw new BusinessException(GalleryErrorCode.STORAGE_QUOTA_EXCEEDED, e);
            }
        }

        String ext = resolveExtension(request.getContentType());
        String mediaPrefix = "PHOTO".equals(request.getMediaType()) ? "photo" : "video";
        String uuid = UUID.randomUUID().toString();
        String r2Key = String.format("gallery/%s/%d/album-%d/%s-%s.%s",
                scopeType.name(), scopeId, albumId, mediaPrefix, uuid, ext);

        Duration ttl = "VIDEO".equals(request.getMediaType()) ? VIDEO_TTL : PHOTO_TTL;
        PresignedUploadResult result = r2StorageService.generateUploadUrl(r2Key, request.getContentType(), ttl);
        log.info("ギャラリーメディア Presigned URL 発行: userId={}, albumId={}, type={}, key={}",
                userId, albumId, request.getMediaType(), r2Key);
        return new MediaUploadUrlResponse(result.uploadUrl(), result.s3Key(), ttl.toSeconds());
    }

    private void validateContentType(String mediaType, String contentType) {
        // ブロックリスト優先（危険な MIME タイプを明示排除）
        if (FileTypeValidator.isBlocked(contentType)) {
            throw new BusinessException(GalleryErrorCode.UNSUPPORTED_CONTENT_TYPE);
        }
        if ("PHOTO".equals(mediaType) && !FileTypeValidator.isAllowed(contentType, ALLOWED_IMAGE_TYPES)) {
            throw new BusinessException(GalleryErrorCode.UNSUPPORTED_CONTENT_TYPE);
        }
        if ("VIDEO".equals(mediaType) && !FileTypeValidator.isAllowed(contentType, ALLOWED_VIDEO_TYPES)) {
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
