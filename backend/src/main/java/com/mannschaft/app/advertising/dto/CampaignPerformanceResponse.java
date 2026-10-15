package com.mannschaft.app.advertising.dto;

import java.math.BigDecimal;
import java.util.List;

public record CampaignPerformanceResponse(
    Long campaignId,
    String campaignName,
    String status,
    String pricingModel,
    PerformanceSummary summary,
    BenchmarkData benchmark,
    List<PerformancePoint> points
) {
    public record PerformanceSummary(
        long totalImpressions,
        long totalClicks,
        BigDecimal avgCtr,
        BigDecimal totalCost,
        BigDecimal avgCpm,
        BigDecimal avgCpc,
        Long conversions,
        BigDecimal conversionRate,
        BigDecimal costPerConversion
    ) {}

    public record BenchmarkData(
        BigDecimal platformAvgCtr,
        Integer yourCtrPercentile,
        BigDecimal sameTemplateAvgCtr,
        BigDecimal sameTemplateAvgCpc
    ) {}

    public record PerformancePoint(
        String period,
        long impressions,
        long clicks,
        BigDecimal ctr,
        BigDecimal cost,
        Long conversions
    ) {}
}
