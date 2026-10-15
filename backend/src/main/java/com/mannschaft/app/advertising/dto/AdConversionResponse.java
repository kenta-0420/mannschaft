package com.mannschaft.app.advertising.dto;

import java.time.LocalDateTime;

/**
 * 広告コンバージョン個別レスポンス。
 */
public record AdConversionResponse(
    Long id,
    Long clickId,
    Long campaignId,
    Long adId,
    String conversionType,
    Long convertedUserId,
    LocalDateTime convertedAt,
    int attributionWindowDays,
    LocalDateTime createdAt
) {}
