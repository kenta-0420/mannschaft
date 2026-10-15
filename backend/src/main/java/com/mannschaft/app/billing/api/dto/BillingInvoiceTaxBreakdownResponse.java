package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.1 課金履歴 API の税内訳（明細行ごと・AC-54）。
 *
 * <p>V196 の {@code billing_invoice_lines} は1行につき1つの税率スナップショットを持つが、
 * 将来の複数税率（軽減税率・地方税）に備えて API 契約は配列で返す。</p>
 */
@Getter
@Builder
@Schema(name = "BillingInvoiceTaxBreakdown", description = "F20.1 明細行の税内訳")
public class BillingInvoiceTaxBreakdownResponse {

    @Schema(description = "税の表示名スナップショット", nullable = true, example = "消費税")
    private final String taxName;

    @Schema(description = "税率（basis points。1000 = 10%）", nullable = true, example = "1000")
    private final Integer taxRateBasisPoints;

    @Schema(description = "税額（最小通貨単位）", example = "100")
    private final Long taxAmount;

    @Schema(description = "税込価格に内包されているか", example = "false")
    private final Boolean includedInPrice;
}
