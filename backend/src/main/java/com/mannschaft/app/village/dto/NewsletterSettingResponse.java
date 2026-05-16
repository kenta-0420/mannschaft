package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村ニュースレター設定の単一レスポンス（F17.1 Phase 3-β-E）。
 */
@Builder
public record NewsletterSettingResponse(
        UUID id,
        UUID villageId,
        VillageNewsletterFrequency frequency,
        boolean isEnabled,
        LocalDateTime lastSentAt,
        LocalDateTime nextScheduledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version
) {
}
