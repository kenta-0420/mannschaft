package com.mannschaft.app.advertising.ranking.dto;

/**
 * 同類チーム備品ランキングアイテムレスポンスDTO。
 */
public record EquipmentTrendingItemResponse(
        int rank,
        String itemName,
        String category,
        int teamCount,
        int consumeEventCount,
        String amazonAsin,
        String replenishUrl
) {}
