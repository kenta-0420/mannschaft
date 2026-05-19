package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.membership.domain.ScopeType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 広告主アカウント詳細レスポンス（SYSTEM_ADMIN向け）。
 *
 * <p>F09.17 Phase 11-d-2 で {@code scopeType}/{@code scopeId} を追加。
 * 既存 {@code organizationId}/{@code organizationName} は {@code scope_type=ORGANIZATION} の場合のみ非 null。
 * Phase 11-e 完了後に削除予定。</p>
 */
public record AdvertiserAccountDetailResponse(
        Long id,
        /**
         * 旧スコープ参照 (Phase 11-e 削除予定)。
         *
         * <p>{@code scope_type=ORGANIZATION} の場合のみ非 null。{@code TEAM} の場合は {@code null}。</p>
         */
        Long organizationId,
        /** F09.17 Phase 11-d-2: スコープ種別。 */
        ScopeType scopeType,
        /** F09.17 Phase 11-d-2: スコープ ID。 */
        Long scopeId,
        /**
         * 旧 organizations.name (Phase 11-e 削除予定)。
         * {@code scope_type=ORGANIZATION} の場合のみ非 null。
         */
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
