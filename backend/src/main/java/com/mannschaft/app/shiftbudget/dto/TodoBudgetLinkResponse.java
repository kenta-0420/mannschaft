package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.mannschaft.app.shiftbudget.entity.TodoBudgetLinkEntity;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F08.7 TODO/プロジェクト 予算紐付 レスポンス DTO（Phase 9-γ）。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.4 / §9.3 に準拠。</p>
 *
 * <p>{@code linkAmount} / {@code linkPercentage} は金額情報を含むため
 * {@link BudgetView.BudgetViewer} レベルから公開する。</p>
 */
@Builder
public record TodoBudgetLinkResponse(

        @JsonView(BudgetView.Public.class)
        @JsonProperty("id")
        Long id,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("project_id")
        Long projectId,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("todo_id")
        Long todoId,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("allocation_id")
        Long allocationId,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("link_amount")
        BigDecimal linkAmount,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("link_percentage")
        BigDecimal linkPercentage,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("currency")
        String currency,

        @JsonView(BudgetView.Public.class)
        @JsonProperty("created_by")
        Long createdBy,

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
    public static TodoBudgetLinkResponse from(TodoBudgetLinkEntity entity) {
        return TodoBudgetLinkResponse.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .todoId(entity.getTodoId())
                .allocationId(entity.getAllocationId())
                .linkAmount(entity.getLinkAmount())
                .linkPercentage(entity.getLinkPercentage())
                .currency(entity.getCurrency())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
