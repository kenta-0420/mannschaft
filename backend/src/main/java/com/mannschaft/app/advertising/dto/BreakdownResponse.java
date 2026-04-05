package com.mannschaft.app.advertising.dto;

import java.math.BigDecimal;
import java.util.List;

public record BreakdownResponse(
    Long campaignId,
    String breakdownBy,
    List<BreakdownItem> items
) {
    public record BreakdownItem(
        String prefecture,
        String template,
        long impressions,
        long clicks,
        BigDecimal ctr,
        BigDecimal cost,
        BigDecimal unitPrice
    ) {}
}
