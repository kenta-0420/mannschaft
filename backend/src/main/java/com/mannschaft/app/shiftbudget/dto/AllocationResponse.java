package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * F08.7 シフト予算割当 レスポンス DTO。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.1 / §9.3 に準拠。</p>
 *
 * <p>{@code @JsonView} による段階的マスキング:</p>
 * <ul>
 *   <li>{@link BudgetView.Public} — 識別子・スコープ・期間（金額情報無し）</li>
 *   <li>{@link BudgetView.BudgetViewer} — 金額情報を解禁</li>
 * </ul>
 */
@Builder
public record AllocationResponse(

        @JsonView(BudgetView.Public.class)
        @JsonProperty("id")
        Long id,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("organization_id")
        Long organizationId,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("team_id")
        Long teamId,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("project_id")
        Long projectId,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("fiscal_year_id")
        Long fiscalYearId,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("budget_category_id")
        Long budgetCategoryId,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("period_start")
        LocalDate periodStart,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("period_end")
        LocalDate periodEnd,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("allocated_amount")
        BigDecimal allocatedAmount,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("consumed_amount")
        BigDecimal consumedAmount,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("confirmed_amount")
        BigDecimal confirmedAmount,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("currency")
        String currency,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("note")
        String note,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("created_by")
        Long createdBy,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("version")
        Long version,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("created_at")
        LocalDateTime createdAt,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("updated_at")
        LocalDateTime updatedAt
) {

    /**
     * Entity からレスポンス DTO に変換する。
     */
    public static AllocationResponse from(ShiftBudgetAllocationEntity entity) {
        return AllocationResponse.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .teamId(entity.getTeamId())
                .projectId(entity.getProjectId())
                .fiscalYearId(entity.getFiscalYearId())
                .budgetCategoryId(entity.getBudgetCategoryId())
                .periodStart(entity.getPeriodStart())
                .periodEnd(entity.getPeriodEnd())
                .allocatedAmount(entity.getAllocatedAmount())
                .consumedAmount(entity.getConsumedAmount())
                .confirmedAmount(entity.getConfirmedAmount())
                .currency(entity.getCurrency())
                .note(entity.getNote())
                .createdBy(entity.getCreatedBy())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
