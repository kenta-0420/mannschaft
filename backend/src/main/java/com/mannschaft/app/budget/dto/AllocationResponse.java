package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 予算配分レスポンス。
 */
public record AllocationResponse(
        Long id,
        Long categoryId,
        String categoryName,
        Integer month,
        BigDecimal amount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
