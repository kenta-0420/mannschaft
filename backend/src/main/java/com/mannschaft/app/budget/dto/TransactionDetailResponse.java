package com.mannschaft.app.budget.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 取引詳細レスポンス。添付ファイル・メモ等を含む。
 */
public record TransactionDetailResponse(
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
        String memo,
        String approvalStatus,
        Long reversedTransactionId,
        UserSummary createdBy,
        UserSummary approvedBy,
        List<AttachmentResponse> attachments,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
