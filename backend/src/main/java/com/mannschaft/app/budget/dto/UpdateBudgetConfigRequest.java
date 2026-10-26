package com.mannschaft.app.budget.dto;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * 予算設定更新リクエスト。
 */
public record UpdateBudgetConfigRequest(

        @DecimalMin(value = "0")
        BigDecimal approvalThreshold,

        @DecimalMin(value = "0")
        BigDecimal warningThresholdPercent,

        @DecimalMin(value = "0")
        BigDecimal criticalThresholdPercent,

        Boolean autoApproveEnabled
) {
}
