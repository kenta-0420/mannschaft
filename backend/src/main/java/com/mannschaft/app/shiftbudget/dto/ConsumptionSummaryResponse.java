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
 *       {@code BUDGET_ADMIN} 保有時のみ実データを返し、それ以外は空配列</li>
 *   <li>{@code flags} は権限の有無に関わらず必ず <strong>配列</strong>。
 *       {@code BUDGET_VIEW} のみで {@code BUDGET_ADMIN} 不保持時は <strong>{@code ["BY_USER_HIDDEN"]}</strong> を含める</li>
 *   <li>{@code alerts} は当該 allocation の警告全件 ({@code triggered_at DESC})、承認済 / 未承認 ともに含める</li>
 * </ul>
 *
 * <p>{@code status} 判定（4段階）:</p>
 * <ul>
 *   <li>{@code consumption_rate < 0.80} → {@code OK}</li>
 *   <li>{@code 0.80 ≤ rate < 1.00} → {@code WARN}</li>
 *   <li>{@code 1.00 ≤ rate < 1.20} → {@code EXCEEDED}</li>
 *   <li>{@code ≥ 1.20} → {@code SEVERE_EXCEEDED}</li>
 * </ul>
 *
 * <p>Phase 9-δ 第3段で {@code @JsonView} を本格化済。Controller が
 * {@link com.mannschaft.app.shiftbudget.view.BudgetView} を {@code MappingJacksonValue} 経由で
 * 切替えることでフィールド単位のマスキングを実現する。</p>
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
        List<AlertResponse> alerts,

        /**
         * 個人別消化内訳。{@code BUDGET_ADMIN} 保有時のみ実データを返却する。
         * BUDGET_VIEW のみのユーザーに対しては Service 層で空配列にし、{@code @JsonView} で更にフィールド除外する。
         */
        @JsonView(BudgetView.BudgetAdmin.class)
        @JsonProperty("by_user")
        List<UserConsumptionDto> byUser
) {
}
