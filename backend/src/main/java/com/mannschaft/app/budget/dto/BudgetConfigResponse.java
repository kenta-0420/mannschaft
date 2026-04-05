package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 予算設定レスポンス。
 */
public record BudgetConfigResponse(
        Long id,
        Long scopeId,
        String scopeType,
        BigDecimal approvalThreshold,
        BigDecimal warningThresholdPercent,
        BigDecimal criticalThresholdPercent,
        boolean autoApproveEnabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
