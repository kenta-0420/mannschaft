package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.membership.domain.ScopeType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 広告主アカウントレスポンス。
 */
public record AdvertiserAccountResponse(
        Long id,
        ScopeType scopeType,
        Long scopeId,
        AdvertiserAccountStatus status,
        String companyName,
        String contactEmail,
        BillingMethod billingMethod,
        BigDecimal creditLimit,
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {
}
