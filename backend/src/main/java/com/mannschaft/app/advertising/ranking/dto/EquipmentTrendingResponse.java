package com.mannschaft.app.advertising.ranking.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同類チーム備品ランキングレスポンスDTO。
 */
public record EquipmentTrendingResponse(
        String teamTemplate,
        String category,
        boolean optOut,
        List<EquipmentTrendingItemResponse> ranking,
        long totalTemplatesTeams,
        LocalDateTime calculatedAt
) {}
