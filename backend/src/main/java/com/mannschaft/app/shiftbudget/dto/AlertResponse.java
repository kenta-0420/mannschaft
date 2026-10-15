package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F08.7 シフト予算 閾値超過警告 レスポンス DTO（API #9 / #10）。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.5 に準拠。</p>
 *
 * <p>{@link BudgetView.BudgetViewer} View で全フィールドが serialize される。
 * 個人別時給情報は含まないため、{@code BUDGET_VIEW} 保有者にも全項目を見せて構わない。
 * {@link ConsumptionSummaryResponse#alerts} 配列要素として埋め込まれる際、
 * 親側の {@code @JsonView(BudgetViewer.class)} によりフィルタされても
 * 自身に同階層 View が付いていることで正しく serialize される。</p>
 */
@Builder
public record AlertResponse(

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("id")
        Long id,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("allocation_id")
        Long allocationId,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("threshold_percent")
        Integer thresholdPercent,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("triggered_at")
        LocalDateTime triggeredAt,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("consumed_amount_at_trigger")
        BigDecimal consumedAmountAtTrigger,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("workflow_request_id")
        Long workflowRequestId,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("acknowledged_at")
        LocalDateTime acknowledgedAt,

        @JsonView(BudgetView.BudgetViewer.class)
        @JsonProperty("acknowledged_by")
        Long acknowledgedBy
) {
    /**
     * Entity からマッピングする（{@code notified_user_ids} は API レスポンスには含めない方針：
     * 設計書 §9.5 の漏洩防止精神に整合）。
     */
    public static AlertResponse from(BudgetThresholdAlertEntity entity) {
        return AlertResponse.builder()
                .id(entity.getId())
                .allocationId(entity.getAllocationId())
                .thresholdPercent(entity.getThresholdPercent())
                .triggeredAt(entity.getTriggeredAt())
                .consumedAmountAtTrigger(entity.getConsumedAmountAtTrigger())
                .workflowRequestId(entity.getWorkflowRequestId())
                .acknowledgedAt(entity.getAcknowledgedAt())
                .acknowledgedBy(entity.getAcknowledgedBy())
                .build();
    }
}
