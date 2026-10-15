package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * コホート分析レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class CohortAnalysisResponse {

    private final String metric;
    private final List<CohortRow> cohorts;

    /**
     * コホート行データ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CohortRow {
        private final String cohortMonth;
        private final int cohortSize;
        private final List<BigDecimal> retention;
    }
}
