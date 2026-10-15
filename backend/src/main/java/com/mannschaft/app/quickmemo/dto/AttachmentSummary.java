package com.mannschaft.app.quickmemo.dto;

import com.mannschaft.app.quickmemo.entity.QuickMemoAttachmentEntity;

/**
 * 添付ファイルサマリ（メモ詳細レスポンスに埋め込む）。
 */
public record AttachmentSummary(
        Long id,
        String s3Key,
        String originalFilename,
        String contentType,
        Integer fileSizeBytes,
        Integer widthPx,
        Integer heightPx,
        Integer sortOrder
) {
    public static AttachmentSummary from(QuickMemoAttachmentEntity entity) {
        return new AttachmentSummary(
                entity.getId(),
                entity.getS3Key(),
                entity.getOriginalFilename(),
                entity.getContentType(),
                entity.getFileSizeBytes(),
                entity.getWidthPx(),
                entity.getHeightPx(),
                entity.getSortOrder()
        );
    }
}
