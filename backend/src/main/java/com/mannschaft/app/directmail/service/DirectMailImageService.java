package com.mannschaft.app.directmail.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.directmail.DirectMailErrorCode;
import com.mannschaft.app.directmail.dto.DirectMailImageUploadResponse;
import com.mannschaft.app.directmail.entity.DirectMailImageUploadEntity;
import com.mannschaft.app.directmail.repository.DirectMailImageUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;
import java.util.UUID;

/**
 * ダイレクトメール画像アップロードサービス。メール本文用の画像管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectMailImageService {

    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    private final DirectMailImageUploadRepository imageUploadRepository;

    /**
     * 画像をアップロードする。
     */
    @Transactional
    public DirectMailImageUploadResponse uploadImage(String scopeType, Long scopeId, Long userId,
                                                      MultipartFile file) {
        // バリデーション
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new BusinessException(DirectMailErrorCode.IMAGE_SIZE_EXCEEDED);
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new BusinessException(DirectMailErrorCode.INVALID_IMAGE_TYPE);
        }

        // S3キー生成
        String s3Key = String.format("direct-mail/%s/%d/images/%s_%s",
                scopeType.toLowerCase(), scopeId, UUID.randomUUID(), file.getOriginalFilename());

        // TODO: S3にアップロード（Pre-signed URLまたはPutObject）

        DirectMailImageUploadEntity entity = DirectMailImageUploadEntity.builder()
                .s3Key(s3Key)
                .fileName(file.getOriginalFilename())
                .fileSize((int) file.getSize())
                .contentType(file.getContentType())
                .uploadedBy(userId)
                .build();

        DirectMailImageUploadEntity saved = imageUploadRepository.save(entity);
        log.info("DM画像アップロード: imageId={}, fileName={}", saved.getId(), file.getOriginalFilename());

        // TODO: Pre-signed URL生成
        String imageUrl = "https://s3.example.com/" + s3Key;

        return new DirectMailImageUploadResponse(
                saved.getId(),
                saved.getS3Key(),
                saved.getFileName(),
                saved.getFileSize(),
                saved.getContentType(),
                imageUrl,
                saved.getCreatedAt()
        );
    }
}
