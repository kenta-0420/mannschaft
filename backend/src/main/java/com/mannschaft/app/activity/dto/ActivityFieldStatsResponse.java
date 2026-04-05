package com.mannschaft.app.activity.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * カスタムフィールド集計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
@Builder
public class ActivityFieldStatsResponse {

    private final String fieldKey;
    private final String fieldLabel;
    private final String unit;
    private final Aggregation aggregation;
    private final List<TrendEntry> trend;

    @Getter
    @RequiredArgsConstructor
    public static class Aggregation {
        private final BigDecimal sum;
        private final BigDecimal avg;
        private final BigDecimal max;
        private final BigDecimal min;
        private final long count;
    }

    @Getter
    @RequiredArgsConstructor
    public static class TrendEntry {
        private final String month;
        private final BigDecimal avg;
    }
}
