package com.mannschaft.app.budget.dto;

import java.time.LocalDateTime;

/**
 * 添付ファイルレスポンス。
 */
public record AttachmentResponse(
        Long id,
        Long transactionId,
        String fileName,
        String fileType,
        Long fileSize,
        String s3Key,
        LocalDateTime createdAt
) {
}
