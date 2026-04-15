package com.mannschaft.app.advertising.ranking.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 備品ランキング集計統計レスポンスDTO（SYSTEM_ADMIN向け）。
 */
public record EquipmentRankingStatsResponse(
        LocalDateTime lastCalculatedAt,
        long totalRankingItems,
        List<String> templatesCovered,
        int optOutTeamCount,
        int excludedItemCount,
        int minTeamCountThreshold,
        long itemsBelowThreshold,
        long itemsWithAsin
) {}
