package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.1 課金履歴 API の請求書発行元（AC-54）。
 */
@Getter
@Builder
@Schema(name = "BillingInvoiceIssuer", description = "F20.1 請求書の発行元")
public class BillingInvoiceIssuerResponse {

    @Schema(description = "発行元名（発行時スナップショット）", example = "Mannschaft")
    private final String name;
}
