package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * セグメント分析レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class SegmentAnalysisResponse {

    private final String segmentBy;
    private final List<SegmentItem> segments;

    /**
     * セグメント別の集計項目。
     */
    @Getter
    @RequiredArgsConstructor
    public static class SegmentItem {
        private final String segment;
        private final int orgCount;
        private final int teamCount;
        private final int userCount;
        private final BigDecimal revenue;
        private final BigDecimal arpu;
        private final BigDecimal churnRate;
    }
}
