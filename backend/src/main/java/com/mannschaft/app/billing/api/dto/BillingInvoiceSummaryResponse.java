package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * F20.1 課金履歴一覧の1行（AC-48〜AC-51）。
 *
 * <p>一覧では明細行・調整を返さない。返してしまうと invoice 件数ぶんの追加クエリが必要になり、
 * AC-57 の「業務 SQL は件数に依存しない」を原理的に満たせなくなる。内訳は明細 API で取る。</p>
 */
@Getter
@Builder
@Schema(name = "BillingInvoiceSummary", description = "F20.1 請求書一覧の1件")
public class BillingInvoiceSummaryResponse {

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

    @Schema(description = "小計")
    private final BillingMoneyResponse subtotal;

    @Schema(description = "値引")
    private final BillingMoneyResponse discount;

    @Schema(description = "税額")
    private final BillingMoneyResponse tax;

    @Schema(description = "合計")
    private final BillingMoneyResponse total;

    @Schema(description = "確定日時", nullable = true)
    private final Instant finalizedAt;

    @Schema(description = "支払日時", nullable = true)
    private final Instant paidAt;
}
