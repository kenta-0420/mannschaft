package com.mannschaft.app.advertising.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * キャンペーン別コンバージョンサマリーレスポンス。
 */
public record AdConversionSummaryResponse(
    Long campaignId,
    long totalConversions,
    long totalClicks,
    BigDecimal conversionRate,
    BigDecimal totalCost,
    BigDecimal costPerConversion,
    Map<String, Long> conversionsByType
) {}
