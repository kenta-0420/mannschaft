package com.mannschaft.app.advertising.dto;

import java.math.BigDecimal;
import java.util.List;

public record CreativeComparisonResponse(
    Long campaignId,
    List<CreativeStats> creatives,
    Winner winner
) {
    public record CreativeStats(
        Long adId,
        String title,
        long impressions,
        long clicks,
        BigDecimal ctr,
        BigDecimal cost,
        Integer conversionRank
    ) {}

    public record Winner(
        Long adId,
        String reason
    ) {}
}
