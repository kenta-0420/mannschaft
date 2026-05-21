package com.mannschaft.app.advertising.dto;

import java.time.LocalDateTime;

/**
 * 広告クリエイティブレスポンス。
 */
public record AdCreativeResponse(
        Long id,
        Long campaignId,
        String title,
        String imageUrl,
        String destinationUrl,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
