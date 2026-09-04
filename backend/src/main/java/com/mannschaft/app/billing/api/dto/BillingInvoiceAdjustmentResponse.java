package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * F20.1 課金履歴 API の調整行（返金・credit note・dispute・AC-54）。
 */
@Getter
@Builder
@Schema(name = "BillingInvoiceAdjustment", description = "F20.1 請求書の調整（返金等）")
public class BillingInvoiceAdjustmentResponse {

    @Schema(description = "調整 ID（UUID）")
    private final String id;

    @Schema(description = "種別（REFUND / CREDIT_NOTE / DISPUTE）", example = "REFUND")
    private final String kind;

    @Schema(description = "ステータス", example = "SUCCEEDED")
    private final String status;

    @Schema(description = "金額")
    private final BillingMoneyResponse amount;

    @Schema(description = "理由（PSP のコード）", nullable = true, example = "requested_by_customer")
    private final String reason;

    @Schema(description = "発効日時")
    private final Instant effectiveAt;
}
