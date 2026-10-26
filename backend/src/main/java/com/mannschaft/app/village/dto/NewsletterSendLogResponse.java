package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村ニュースレター配信履歴レスポンス（F17.1 Phase 3-β-E）。
 */
@Builder
public record NewsletterSendLogResponse(
        UUID id,
        UUID newsletterId,
        LocalDateTime sentAt,
        int recipientCount,
        int successCount,
        int failureCount
) {
}
