package com.mannschaft.app.billing.api.dto;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import java.time.Instant;
import java.util.UUID;
public record PriceRevisionSummaryResponse(UUID id, BillingProductKind productKind, String productKey, EntitlementScopeKind scopeKind, long revisionNo, BillingPriceVersionStatus status, Instant effectiveFrom, Instant effectiveUntil) {}
