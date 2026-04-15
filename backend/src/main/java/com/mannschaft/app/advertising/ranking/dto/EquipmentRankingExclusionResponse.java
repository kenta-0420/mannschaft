package com.mannschaft.app.advertising.ranking.dto;

import java.time.LocalDateTime;

/**
 * 備品ランキング除外設定レスポンスDTO（SYSTEM_ADMIN向け）。
 */
public record EquipmentRankingExclusionResponse(
        long id,
        String exclusionType,
        Long teamId,
        String teamName,
        String normalizedName,
        String reason,
        Long excludedByUserId,
        LocalDateTime createdAt
) {}
