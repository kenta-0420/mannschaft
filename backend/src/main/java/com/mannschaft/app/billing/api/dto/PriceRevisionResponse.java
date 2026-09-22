package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code POST /price-revisions} レスポンス（決定4）。 */
@Getter
@Builder
public class PriceRevisionResponse {

    private UUID id;
    private BillingProductKind productKind;
    private String productKey;
    private EntitlementScopeKind scopeKind;
    private long revisionNo;
    private String catalogRevision;
    private BillingPriceVersionStatus status;
    private Instant effectiveFrom;
    private Instant effectiveUntil;
    private List<PriceRevisionBandResponse> bands;
}
