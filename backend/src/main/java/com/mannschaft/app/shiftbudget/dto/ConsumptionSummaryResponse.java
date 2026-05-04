package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * F08.7 消化サマリ レスポンス DTO。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.3 / §9.3 に準拠。</p>
 *
 * <p>形状確定ルール（v1.2）:</p>
 * <ul>
 *   <li>{@code by_user} は権限の有無に関わらず必ず <strong>配列</strong>（{@code null} 不可）。
 *       Phase 9-β 中はマスター御裁可 Q2 により <strong>常に空配列</strong> を返却する</li>
 *   <li>{@code flags} は権限の有無に関わらず必ず <strong>配列</strong>。
 *       Phase 9-β 中は <strong>常に {@code ["BY_USER_HIDDEN"]}</strong> を含める</li>
 *   <li>{@code alerts} は警告機能未実装のため Phase 9-δ までは常に空配列</li>
 * </ul>
 *
 * <p>{@code status} 判定（4段階）:</p>
 * <ul>
 *   <li>{@code consumption_rate < 0.80} → {@code OK}</li>
 *   <li>{@code 0.80 ≤ rate < 1.00} → {@code WARN}</li>
 *   <li>{@code 1.00 ≤ rate < 1.20} → {@code EXCEEDED}</li>
 *   <li>{@code ≥ 1.20} → {@code SEVERE_EXCEEDED}</li>
 * </ul>
 */
@Builder
public record ConsumptionSummaryResponse(

        @JsonView(BudgetView.Public.class)
        @JsonProperty("allocation_id")
        Long allocationId,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("allocated_amount")
        BigDecimal allocatedAmount,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("consumed_amount")
        BigDecimal consumedAmount,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("confirmed_amount")
        BigDecimal confirmedAmount,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("planned_amount")
        BigDecimal plannedAmount,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("remaining_amount")
        BigDecimal remainingAmount,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("consumption_rate")
        BigDecimal consumptionRate,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("status")
        String status,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("flags")
        List<String> flags,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("alerts")
        List<Object> alerts,

        /**
         * 個人別消化内訳。Phase 9-β 中は常に空配列。
         * 9-δ で BUDGET_ADMIN クリーンカット移行時に実データを返却する設計。
         */
        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("by_user")
        List<UserConsumptionDto> byUser
) {
}
