package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.membership.domain.ScopeType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 広告主アカウント詳細レスポンス（SYSTEM_ADMIN向け）。
 *
 * <p>F09.17 Phase 11-e で {@code organizationId}/{@code organizationName} を削除し、
 * {@code scopeType}/{@code scopeId}/{@code scopeName} を主役とした。</p>
 */
public record AdvertiserAccountDetailResponse(
        Long id,
        /** F09.17 Phase 11-d-2: スコープ種別。 */
        ScopeType scopeType,
        /** F09.17 Phase 11-d-2: スコープ ID。 */
        Long scopeId,
        /**
         * スコープ名 (ORGANIZATION の場合は組織名、TEAM の場合はチーム名)。
         * 解決できない場合は scopeId の文字列表現。
         */
        String scopeName,
        AdvertiserAccountStatus status,
        String companyName,
        String contactEmail,
        BillingMethod billingMethod,
        BigDecimal creditLimit,
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {
}
