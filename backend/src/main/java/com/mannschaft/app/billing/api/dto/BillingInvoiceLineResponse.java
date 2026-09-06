package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * F20.1 課金履歴 API の請求明細行（AC-54）。
 *
 * <p>すべて発行時点のスナップショットであり、マスタの現在値では復元しない
 * （過去の請求書は後からのマスタ変更で書き換わってはならない）。</p>
 */
@Getter
@Builder
@Schema(name = "BillingInvoiceLine", description = "F20.1 請求明細行")
public class BillingInvoiceLineResponse {

    @Schema(description = "明細行 ID（UUID）")
    private final String id;

    @Schema(description = "明細の説明（発行時スナップショット）", example = "プラン利用料")
    private final String description;

    @Schema(description = "数量", example = "1.000")
    private final BigDecimal quantity;

    @Schema(description = "税抜金額（最小通貨単位）", example = "10000")
    private final Long amountExcludingTax;

    @Schema(description = "値引額（最小通貨単位）", example = "0")
    private final Long discountAmount;

    @Schema(description = "税込金額（最小通貨単位）", example = "11000")
    private final Long amountIncludingTax;

    @Schema(description = "税内訳")
    private final List<BillingInvoiceTaxBreakdownResponse> taxes;

    @Schema(description = "対象期間の開始", nullable = true)
    private final Instant periodStart;

    @Schema(description = "対象期間の終了", nullable = true)
    private final Instant periodEnd;
}
