package com.mannschaft.app.repairplan.dto;

import java.util.List;
import java.util.Map;

/**
 * 地層タイムラインレスポンス（F08.8 Phase 3）。
 *
 * <p>{@code amountByYearAndCategory}: year(int文字列) → category → 合計金額(Long)。
 * {@code chairpersonByYear}: year(int文字列) → 理事長displayName（在任者不明の場合 null）。
 * {@code cpiTrendByYear}: year(int文字列) → CPI指数（2024年=100.0、国交省R5 1.5%/年）。</p>
 */
public record RepairPlanTimelineResponse(
        String scopeType,
        Long scopeId,
        int yearFrom,
        int yearTo,
        List<Integer> labels,
        List<String> categories,
        Map<String, Map<String, Long>> amountByYearAndCategory,
        Map<String, Long> totalByYear,
        Map<String, String> chairpersonByYear,
        Map<String, Double> cpiTrendByYear
) {}
