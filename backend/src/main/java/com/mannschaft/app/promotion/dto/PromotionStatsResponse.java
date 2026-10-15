package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * プロモーション効果測定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PromotionStatsResponse {

    private final Long promotionId;
    private final Integer targetCount;
    private final Integer deliveredCount;
    private final Integer openedCount;
    private final Integer skippedCount;
    private final Integer failedCount;
    private final Double openRate;
    private final List<DailySummary> dailySummaries;

    @Getter
    @RequiredArgsConstructor
    public static class DailySummary {
        private final String date;
        private final Integer deliveredCount;
        private final Integer openedCount;
        private final Integer failedCount;
    }
}
