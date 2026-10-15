package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * モジュールランキングレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class ModuleRankingResponse {

    private final List<ModuleRank> modules;

    /**
     * モジュール別ランキング項目。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ModuleRank {
        private final Long moduleId;
        private final String moduleName;
        private final int activeTeams;
        private final BigDecimal revenue;
        private final BigDecimal revenueSharePct;
        private final BigDecimal growthRate;
        private final BigDecimal churnRate;
    }
}
