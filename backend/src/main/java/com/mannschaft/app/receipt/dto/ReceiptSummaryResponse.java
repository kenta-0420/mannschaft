package com.mannschaft.app.receipt.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 領収書一覧用サマリーレスポンスDTO。
 */
@Getter
@Builder
public class ReceiptSummaryResponse {
    private final Long id;
    private final String receiptNumber;
    private final String recipientName;
    private final String description;
    private final BigDecimal amount;
    private final Boolean isQualifiedInvoice;
    private final LocalDate paymentDate;
    private final LocalDateTime issuedAt;
    private final Boolean isVoided;
    private final Boolean sealStamped;
}
