package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 取引レスポンス。
 */
public record TransactionResponse(
        Long id,
        Long fiscalYearId,
        Long categoryId,
        String categoryName,
        String transactionType,
        BigDecimal amount,
        LocalDate transactionDate,
        String description,
        String paymentMethod,
        String reference,
        String approvalStatus,
        UserSummary createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
