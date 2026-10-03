package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingTaxBehavior;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * {@code POST/GET /price-revisions} レスポンスの band 部分。
 *
 * <p>create 時点では {@code status=DRAFT}・{@code stripePriceRef}/{@code provisionErrorCode} は null・
 * {@code provisionAttempts=0}。Provision 系（D群/E群/F群）が値を埋める。秘密・raw Stripe payload は
 * 一切保持しない（AC-58）。</p>
 */
@Getter
@Builder
public class PriceRevisionBandResponse {

    private UUID id;
    private int bandNo;
    private int minMembers;
    private Integer maxMembers;
    private long inputAmount;
    private BillingTaxBehavior taxBehavior;
    private String taxCode;
    private long amountExcludingTax;
    private long taxAmount;
    private long amountIncludingTax;
    private int taxRateBasisPoints;
    private BillingPriceVersionStatus status;
    private String stripePriceRef;
    private String provisionErrorCode;
    private int provisionAttempts;
}
