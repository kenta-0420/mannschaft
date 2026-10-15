package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.PricingModel;

import java.math.BigDecimal;

public record InvoiceItemResponse(
    Long campaignId,
    String campaignName,
    PricingModel pricingModel,
    long impressions,
    long clicks,
    BigDecimal unitPrice,
    BigDecimal subtotal
) {}
