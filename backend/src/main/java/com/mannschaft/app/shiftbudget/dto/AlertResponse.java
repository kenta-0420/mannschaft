package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F08.7 シフト予算 閾値超過警告 レスポンス DTO（API #9 / #10）。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.5 に準拠。</p>
 */
@Builder
public record AlertResponse(

        @JsonProperty("id")
        Long id,

        @JsonProperty("allocation_id")
        Long allocationId,

        @JsonProperty("threshold_percent")
        Integer thresholdPercent,

        @JsonProperty("triggered_at")
        LocalDateTime triggeredAt,

        @JsonProperty("consumed_amount_at_trigger")
        BigDecimal consumedAmountAtTrigger,

        @JsonProperty("workflow_request_id")
        Long workflowRequestId,

        @JsonProperty("acknowledged_at")
        LocalDateTime acknowledgedAt,

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
