package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.knowledgebase.entity.KbImageUploadEntity;
import com.mannschaft.app.knowledgebase.repository.KbImageUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ナレッジベース画像サービス。
 * 画像アップロードURL生成・ページへの紐付けを担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class KbImageService {

    /** 許可するContent-Type */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif"
    );

    /** 最大ファイルサイズ: 10MB */
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    /** アップロードURL有効期限 */
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);

    /** S3キーからURLを抽出するためのパターン（S3キーのプレフィックスで識別） */
    private static final Pattern S3_KEY_PATTERN =
            Pattern.compile("kb/images/([^\\s\"'<>]+)");

    private final KbImageUploadRepository imageUploadRepository;
    private final StorageService storageService;

    // ========================================
    // Response レコード型
    // ========================================

    public record ImageUploadUrlResult(
            String uploadUrl,
            String s3Key,
            String imageUrl
    ) {}

    // ========================================
    // メソッド
    // ========================================

    /**
     * 画像アップロード用のPre-signed URLを生成する。
     * contentTypeとfileSizeのバリデーションを行い、KbImageUploadEntityをINSERTする。
     */
    @Transactional
    public ApiResponse<ImageUploadUrlResult> generateUploadUrl(String scopeType, Long scopeId,
                                                                Long uploaderId,
                                                                String contentType, long fileSize) {
        // Content-Typeバリデーション
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // ファイルサイズバリデーション
        if (fileSize > MAX_FILE_SIZE) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // S3キー生成
        String extension = resolveExtension(contentType);
        String s3Key = "kb/images/" + scopeType + "/" + scopeId + "/"
                + System.currentTimeMillis() + "_" + uploaderId + "." + extension;

        // Pre-signed URL生成
        PresignedUploadResult presigned = storageService.generateUploadUrl(s3Key, contentType, UPLOAD_URL_TTL);

        // KbImageUploadEntityをINSERT（kb_page_id=null）
        KbImageUploadEntity entity = KbImageUploadEntity.builder()
                .kbPageId(null)
                .uploaderId(uploaderId)
                .s3Key(s3Key)
                .fileSize(fileSize)
                .contentType(contentType)
                .build();
        imageUploadRepository.save(entity);

        // imageUrlはS3キーから導出（実際のURLはStorageServiceの実装依存）
        String imageUrl = presigned.s3Key();

        log.info("KB画像アップロードURLを生成しました: s3Key={}, uploaderId={}", s3Key, uploaderId);
        return ApiResponse.of(new ImageUploadUrlResult(presigned.uploadUrl(), s3Key, imageUrl));
    }

    /**
     * ページ本文中のS3 URLを解析し、kb_image_uploads.kb_page_idを更新する。
     */
    @Transactional
    public void associateImages(Long pageId, String bodyContent) {
        if (bodyContent == null || bodyContent.isBlank()) {
            return;
        }

        Matcher matcher = S3_KEY_PATTERN.matcher(bodyContent);
        while (matcher.find()) {
            String s3Key = "kb/images/" + matcher.group(1);
            imageUploadRepository.findByS3Key(s3Key).ifPresent(image -> {
                if (image.getKbPageId() == null) {
                    KbImageUploadEntity updated = image.toBuilder()
                            .kbPageId(pageId)
                            .build();
                    imageUploadRepository.save(updated);
                    log.debug("KB画像をページに紐付けました: s3Key={}, pageId={}", s3Key, pageId);
                }
            });
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private String resolveExtension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "bin";
        };
    }
}
