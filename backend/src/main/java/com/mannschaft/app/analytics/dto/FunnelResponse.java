package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * ファネル分析レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class FunnelResponse {

    private final LocalDate date;
    private final List<FunnelStageItem> stages;

    /**
     * ファネルの各ステージ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class FunnelStageItem {
        private final String stage;
        private final int userCount;
        private final BigDecimal conversionRate;
    }
}
