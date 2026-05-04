package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.dto.BlogMediaUploadUrlRequest;
import com.mannschaft.app.cms.dto.BlogMediaUploadUrlResponse;
import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.files.dto.StartMultipartUploadRequest;
import com.mannschaft.app.files.dto.StartMultipartUploadResponse;
import com.mannschaft.app.files.service.MultipartUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ブログメディア（画像・動画）アップロード管理サービス。
 * IMAGE → Presigned PUT URL（単発）を発行する。
 * VIDEO → Multipart Upload を開始し uploadId を返す。
 * 孤立メディアの日次クリーンアップも担う。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogMediaService {

    // ==================== 定数 ====================

    /** 許可する画像 MIME タイプ */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic"
    );

    /** 許可する動画 MIME タイプ */
    private static final Set<String> ALLOWED_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime"
    );

    /** IMAGE 単発 PUT の上限サイズ（100MB） */
    private static final long SINGLE_UPLOAD_MAX_BYTES = 100L * 1024 * 1024;

    /** VIDEO のアップロード上限サイズ（1GB） */
    private static final long VIDEO_MAX_BYTES = 1024L * 1024 * 1024;

    /** 1 記事あたりの動画上限本数 */
    private static final int MAX_VIDEO_PER_POST = 3;

    /** 1 記事あたりの画像上限枚数 */
    private static final int MAX_IMAGE_PER_POST = 30;

    /** Multipart 推奨パートサイズ（10MB） */
    private static final long RECOMMENDED_PART_SIZE = 10L * 1024 * 1024;

    /** Presigned PUT URL の有効期限 */
    private static final Duration IMAGE_UPLOAD_TTL = Duration.ofSeconds(600);

    /** Presigned URL の有効秒数（レスポンス用） */
    private static final int IMAGE_UPLOAD_TTL_SECONDS = 600;

    /** R2 オブジェクトキープレフィックステンプレート: blog/{scopeType}/{scopeId}/ */
    private static final String BLOG_PREFIX_TEMPLATE = "blog/%s/%d/";

    /** F13 Phase 4-δ: storage_usage_logs.reference_type に記録するテーブル名。 */
    private static final String REFERENCE_TYPE = "blog_media_uploads";

    // ==================== 依存 ====================

    private final R2StorageService r2StorageService;
    private final MultipartUploadService multipartUploadService;
    private final BlogMediaUploadRepository blogMediaUploadRepository;
    /** F13 Phase 4-δ: 統合ストレージクォータサービス。 */
    private final StorageQuotaService storageQuotaService;

    // ==================== 公開メソッド ====================

    /**
     * ブログ記事本文に埋め込むメディアのアップロード URL を発行する。
     * IMAGE → Presigned PUT URL（単発）を発行する。
     * VIDEO → Multipart Upload を開始し uploadId を返す。
     *
     * <p><b>F13 Phase 4-δ</b>: アップロード URL 発行前に {@link StorageQuotaService#checkQuota} で
     * クォータを確認する。超過時は {@link CmsErrorCode#MEDIA_QUOTA_EXCEEDED} をスローする。</p>
     *
     * @param uploaderId アップロードを行うユーザー ID
     * @param req        リクエスト情報
     * @return アップロード URL 発行レスポンス
     */
    @Transactional
    public BlogMediaUploadUrlResponse generateUploadUrl(Long uploaderId, BlogMediaUploadUrlRequest req) {
        validateRequest(req);

        // F13 Phase 4-δ: 統合クォータチェック（presign 前）
        StorageScopeType scopeType = StorageScopeType.valueOf(req.getScopeType().toUpperCase());
        try {
            storageQuotaService.checkQuota(scopeType, req.getScopeId(), req.getFileSize());
        } catch (StorageQuotaExceededException e) {
            log.info("ブログメディアのクォータ超過: uploaderId={}, scope={}/{}, requested={}",
                    uploaderId, scopeType, req.getScopeId(), e.getRequestedBytes());
            throw new BusinessException(CmsErrorCode.MEDIA_QUOTA_EXCEEDED, e);
        }

        String prefix = String.format(BLOG_PREFIX_TEMPLATE, req.getScopeType().toUpperCase(), req.getScopeId());

        if ("IMAGE".equals(req.getMediaType())) {
            return handleImageUpload(uploaderId, req, prefix, scopeType);
        } else {
            return handleVideoUpload(uploaderId, req, prefix, scopeType);
        }
    }

    /**
     * 孤立メディアのクリーンアップ（日次バッチ）。
     * blog_post_id IS NULL かつ 72 時間以上経過したレコードを R2 から削除して物理削除する。
     *
     * <p><b>F13 Phase 4-δ</b>: R2 削除成功後に {@link StorageQuotaService#recordDeletion} で
     * 使用量を減算する。s3Key のプレフィックス（{@code blog/{SCOPE_TYPE}/{SCOPE_ID}/}）から
     * スコープを復元する。スコープ解析に失敗した場合は警告ログのみで減算をスキップする。</p>
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupOrphanMedia() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(72);
        List<BlogMediaUploadEntity> orphans = blogMediaUploadRepository
                .findByBlogPostIdIsNullAndCreatedAtBefore(cutoff);

        for (BlogMediaUploadEntity orphan : orphans) {
            try {
                r2StorageService.delete(orphan.getS3Key());
                if (orphan.getThumbnailR2Key() != null) {
                    r2StorageService.delete(orphan.getThumbnailR2Key());
                }
                // F13 Phase 4-δ: 使用量減算（s3Key からスコープを復元）
                if (orphan.getFileSize() != null && orphan.getFileSize() > 0) {
                    resolveScopeFromKey(orphan.getS3Key()).ifPresent(scope ->
                            storageQuotaService.recordDeletion(
                                    scope.scopeType(), scope.scopeId(),
                                    orphan.getFileSize(), StorageFeatureType.CMS,
                                    REFERENCE_TYPE, orphan.getId(), orphan.getUploaderId()));
                }
            } catch (Exception e) {
                // R2 削除失敗は警告ログのみ（DB 削除は続行する）
                log.warn("孤立メディアの R2 削除に失敗しました（DB 削除は続行）: mediaId={}, key={}",
                        orphan.getId(), orphan.getS3Key(), e);
            }
        }

        blogMediaUploadRepository.deleteAll(orphans);
        log.info("孤立メディアのクリーンアップ完了: 削除件数={}", orphans.size());
    }

    /**
     * R2 キー（{@code blog/{SCOPE_TYPE}/{SCOPE_ID}/...}）からスコープを復元する。
     *
     * @param s3Key R2 オブジェクトキー
     * @return スコープ情報（解析失敗時は空）
     */
    private java.util.Optional<ScopeResolution> resolveScopeFromKey(String s3Key) {
        // blog/{SCOPE_TYPE}/{SCOPE_ID}/... 形式を解析
        if (s3Key == null || !s3Key.startsWith("blog/")) {
            log.warn("ブログメディア削除: s3Key のフォーマットが不正のためクォータ減算をスキップ: key={}", s3Key);
            return java.util.Optional.empty();
        }
        String[] parts = s3Key.split("/");
        if (parts.length < 3) {
            log.warn("ブログメディア削除: s3Key のセグメント数が不足のためクォータ減算をスキップ: key={}", s3Key);
            return java.util.Optional.empty();
        }
        try {
            StorageScopeType scopeType = StorageScopeType.valueOf(parts[1]);
            Long scopeId = Long.parseLong(parts[2]);
            return java.util.Optional.of(new ScopeResolution(scopeType, scopeId));
        } catch (IllegalArgumentException e) {
            log.warn("ブログメディア削除: s3Key のスコープ解析に失敗したためクォータ減算をスキップ: key={}", s3Key);
            return java.util.Optional.empty();
        }
    }

    /**
     * ストレージスコープの解決結果。
     */
    public record ScopeResolution(StorageScopeType scopeType, Long scopeId) {}

    // ==================== プライベートメソッド ====================

    /**
     * リクエストのバリデーションを行う。
     * MIME タイプ・サイズ・記事あたり枚数／本数を検証する。
     *
     * @param req リクエスト
     */
    private void validateRequest(BlogMediaUploadUrlRequest req) {
        if ("IMAGE".equals(req.getMediaType())) {
            if (!ALLOWED_IMAGE_TYPES.contains(req.getContentType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "サポートされていない画像形式です: " + req.getContentType());
            }
            if (req.getFileSize() > SINGLE_UPLOAD_MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "画像サイズが上限（100MB）を超えています");
            }
            // 記事あたりの画像枚数上限チェック
            if (req.getBlogPostId() != null) {
                int count = blogMediaUploadRepository.countByBlogPostIdAndMediaType(
                        req.getBlogPostId(), "IMAGE");
                if (count >= MAX_IMAGE_PER_POST) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "1記事あたりの画像上限（" + MAX_IMAGE_PER_POST + "枚）を超えています");
                }
            }
        } else if ("VIDEO".equals(req.getMediaType())) {
            if (!ALLOWED_VIDEO_TYPES.contains(req.getContentType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "サポートされていない動画形式です: " + req.getContentType());
            }
            if (req.getFileSize() > VIDEO_MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "動画サイズが上限（1GB）を超えています。大容量動画はファイル共有機能をご利用ください");
            }
            // 記事あたりの動画本数上限チェック
            if (req.getBlogPostId() != null) {
                int count = blogMediaUploadRepository.countByBlogPostIdAndMediaType(
                        req.getBlogPostId(), "VIDEO");
                if (count >= MAX_VIDEO_PER_POST) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "1記事あたりの動画上限（" + MAX_VIDEO_PER_POST + "本）を超えています");
                }
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mediaType は IMAGE または VIDEO を指定してください");
        }
    }

    /**
     * 画像アップロード URL 発行処理。
     * R2 の Presigned PUT URL を発行し、blog_media_uploads に INSERT する。
     *
     * <p><b>F13 Phase 4-δ</b>: INSERT 完了直後に {@link StorageQuotaService#recordUpload} で
     * 使用量を加算する。IMAGE は Presigned URL 発行 + DB INSERT が同時に行われるため、
     * presign 時に recordUpload する（confirm ステップはない）。</p>
     *
     * @param uploaderId アップロードを行うユーザー ID
     * @param req        リクエスト情報
     * @param prefix     R2 オブジェクトキープレフィックス
     * @param scopeType  解決済みストレージスコープ種別
     * @return レスポンス（uploadUrl, expiresIn を設定。uploadId は null）
     */
    private BlogMediaUploadUrlResponse handleImageUpload(
            Long uploaderId, BlogMediaUploadUrlRequest req, String prefix,
            StorageScopeType scopeType) {

        // R2 オブジェクトキー生成: {prefix}{uuid}.{ext}
        String ext = resolveImageExtension(req.getContentType());
        String uuid = UUID.randomUUID().toString();
        String r2Key = prefix + uuid + "." + ext;

        // R2StorageService.generateUploadUrl() で Presigned PUT URL 発行
        PresignedUploadResult result = r2StorageService.generateUploadUrl(r2Key, req.getContentType(), IMAGE_UPLOAD_TTL);

        // blog_media_uploads に INSERT
        BlogMediaUploadEntity entity = BlogMediaUploadEntity.builder()
                .blogPostId(req.getBlogPostId())
                .uploaderId(uploaderId)
                .mediaType("IMAGE")
                .s3Key(r2Key)
                .fileSize(req.getFileSize())
                .contentType(req.getContentType())
                .processingStatus("READY")
                .build();
        BlogMediaUploadEntity saved = blogMediaUploadRepository.save(entity);

        log.info("画像アップロード Presigned URL 発行: uploaderId={}, mediaId={}, key={}",
                uploaderId, saved.getId(), r2Key);

        // F13 Phase 4-δ: 使用量加算（IMAGE は presign 発行＋INSERT 完了を確定とみなす）
        storageQuotaService.recordUpload(
                scopeType, req.getScopeId(), req.getFileSize(),
                StorageFeatureType.CMS,
                REFERENCE_TYPE, saved.getId(), uploaderId);

        return BlogMediaUploadUrlResponse.builder()
                .mediaId(saved.getId())
                .mediaType("IMAGE")
                .fileKey(r2Key)
                .uploadUrl(result.uploadUrl())
                .expiresIn(IMAGE_UPLOAD_TTL_SECONDS)
                .build();
    }

    /**
     * 動画アップロード（Multipart Upload 開始）処理。
     * Multipart Upload を開始し、blog_media_uploads に INSERT する。
     *
     * <p><b>F13 Phase 4-δ</b>: INSERT 完了直後に {@link StorageQuotaService#recordUpload} で
     * 使用量を加算する。Multipart Upload は Multipart Complete（クライアント側）で確定するが、
     * checkQuota は presign 時（本メソッド呼び出し前）に実施済みのため、ここでは recordUpload のみ行う。</p>
     *
     * @param uploaderId アップロードを行うユーザー ID
     * @param req        リクエスト情報
     * @param prefix     R2 オブジェクトキープレフィックス
     * @param scopeType  解決済みストレージスコープ種別
     * @return レスポンス（uploadId, partSize を設定。uploadUrl は null）
     */
    private BlogMediaUploadUrlResponse handleVideoUpload(
            Long uploaderId, BlogMediaUploadUrlRequest req, String prefix,
            StorageScopeType scopeType) {

        // Multipart Upload を開始する
        // fileName は contentType から拡張子を付けて生成（startUpload 内部でキー生成に使用）
        String ext = resolveVideoExtension(req.getContentType());
        String fileName = UUID.randomUUID() + "." + ext;

        StartMultipartUploadRequest startReq = new StartMultipartUploadRequest(
                null,                    // folderId（blog 経由なので不要）
                fileName,                // fileName
                req.getContentType(),    // contentType
                req.getFileSize(),       // fileSize
                1,                       // partCount（仮値。クライアントが実際のパート数を決定する）
                RECOMMENDED_PART_SIZE,   // partSize
                prefix                   // targetPrefix
        );

        StartMultipartUploadResponse startResponse = multipartUploadService.startUpload(uploaderId, startReq);

        // blog_media_uploads に INSERT（processingStatus=PENDING: Workers による後処理を待つ）
        BlogMediaUploadEntity entity = BlogMediaUploadEntity.builder()
                .blogPostId(req.getBlogPostId())
                .uploaderId(uploaderId)
                .mediaType("VIDEO")
                .s3Key(startResponse.getFileKey())
                .fileSize(req.getFileSize())
                .contentType(req.getContentType())
                .processingStatus("PENDING")
                .build();
        BlogMediaUploadEntity saved = blogMediaUploadRepository.save(entity);

        log.info("動画 Multipart Upload 開始: uploaderId={}, mediaId={}, uploadId={}, key={}",
                uploaderId, saved.getId(), startResponse.getUploadId(), startResponse.getFileKey());

        // F13 Phase 4-δ: 使用量加算（Multipart 開始＋DB INSERT 完了を確定とみなす）
        storageQuotaService.recordUpload(
                scopeType, req.getScopeId(), req.getFileSize(),
                StorageFeatureType.CMS,
                REFERENCE_TYPE, saved.getId(), uploaderId);

        return BlogMediaUploadUrlResponse.builder()
                .mediaId(saved.getId())
                .mediaType("VIDEO")
                .fileKey(startResponse.getFileKey())
                .uploadId(startResponse.getUploadId())
                .partSize(RECOMMENDED_PART_SIZE)
                .build();
    }

    /**
     * 画像 MIME タイプから拡張子を返す。
     *
     * @param contentType MIME タイプ
     * @return 拡張子（ドットなし）
     */
    private String resolveImageExtension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/heic" -> "heic";
            default -> "bin";
        };
    }

    /**
     * 動画 MIME タイプから拡張子を返す。
     *
     * @param contentType MIME タイプ
     * @return 拡張子（ドットなし）
     */
    private String resolveVideoExtension(String contentType) {
        return switch (contentType) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> "bin";
        };
    }
}
