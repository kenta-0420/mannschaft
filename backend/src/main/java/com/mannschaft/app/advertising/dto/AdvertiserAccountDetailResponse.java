package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.BillingMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 広告主アカウント詳細レスポンス（SYSTEM_ADMIN向け）。
 */
public record AdvertiserAccountDetailResponse(
        Long id,
        Long organizationId,
        String organizationName,
        AdvertiserAccountStatus status,
        String companyName,
        String contactEmail,
        BillingMethod billingMethod,
        BigDecimal creditLimit,
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {
}
