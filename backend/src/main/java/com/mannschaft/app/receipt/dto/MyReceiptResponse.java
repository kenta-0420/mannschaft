package com.mannschaft.app.receipt.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * マイページ用領収書レスポンスDTO。
 */
@Getter
@Builder
public class MyReceiptResponse {
    private final Long id;
    private final String receiptNumber;
    private final String scopeName;
    private final String description;
    private final BigDecimal amount;
    private final Boolean isQualifiedInvoice;
    private final LocalDate paymentDate;
    private final LocalDateTime issuedAt;
    private final Boolean isVoided;
    private final String pdfDownloadUrl;
}
