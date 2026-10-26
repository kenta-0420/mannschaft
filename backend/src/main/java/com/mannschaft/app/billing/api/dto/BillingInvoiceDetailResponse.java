package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * F20.1 課金履歴の明細（AC-54）。
 *
 * <p><b>意図的に返さないもの</b>: Stripe の hosted invoice URL / PDF URL、請求先住所の全文、
 * webhook payload。表示に不要であり、監査にも残さない方針（AC-60）と揃える。</p>
 */
@Getter
@Builder
@Schema(name = "BillingInvoiceDetail", description = "F20.1 請求書の明細")
public class BillingInvoiceDetailResponse {

    @Schema(description = "請求書 ID（UUID）")
    private final String id;

    @Schema(description = "スコープ種別（USER / TEAM / ORG）", example = "USER")
    private final String scopeKind;

    @Schema(description = "スコープ ID", example = "123")
    private final Long scopeId;

    @Schema(description = "請求書ステータス", example = "PAID")
    private final String status;

    @Schema(description = "請求理由（Stripe billing_reason）", example = "subscription_cycle")
    private final String billingReason;

    @Schema(description = "対象期間の開始", nullable = true)
    private final Instant periodStart;

    @Schema(description = "対象期間の終了", nullable = true)
    private final Instant periodEnd;

    @Schema(description = "発行元")
    private final BillingInvoiceIssuerResponse issuer;

    @Schema(description = "請求先名（発行時スナップショット）", nullable = true)
    private final String billingName;

    @Schema(description = "小計")
    private final BillingMoneyResponse subtotal;

    @Schema(description = "値引")
    private final BillingMoneyResponse discount;

    @Schema(description = "税額")
    private final BillingMoneyResponse tax;

    @Schema(description = "合計")
    private final BillingMoneyResponse total;

    @Schema(description = "明細行")
    private final List<BillingInvoiceLineResponse> lines;

    @Schema(description = "調整（返金・credit note・dispute）")
    private final List<BillingInvoiceAdjustmentResponse> adjustments;

    @Schema(description = "確定日時", nullable = true)
    private final Instant finalizedAt;

    @Schema(description = "支払日時", nullable = true)
    private final Instant paidAt;

    @Schema(description = "無効化日時", nullable = true)
    private final Instant voidedAt;
}
