package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.membership.domain.ScopeType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 広告主アカウントレスポンス。
 *
 * <p>F09.17 Phase 11-e で {@code organizationId} を削除し、
 * {@code scopeType}/{@code scopeId} を主役とした。</p>
 */
public record AdvertiserAccountResponse(
        Long id,
        /** F09.17 Phase 11-d-2: スコープ種別。 */
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
