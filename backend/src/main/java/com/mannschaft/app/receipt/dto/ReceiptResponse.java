package com.mannschaft.app.receipt.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 領収書レスポンスDTO。
 */
@Getter
@Builder
public class ReceiptResponse {
    private final Long id;
    private final String receiptNumber;
    private final String status;
    private final String recipientName;
    private final String recipientPostalCode;
    private final String recipientAddress;
    private final String issuerName;
    private final String issuerPostalCode;
    private final String issuerAddress;
    private final String issuerPhone;
    private final Boolean isQualifiedInvoice;
    private final String invoiceRegistrationNumber;
    private final String description;
    private final BigDecimal amount;
    private final BigDecimal taxRate;
    private final BigDecimal taxAmount;
    private final BigDecimal amountExclTax;
    private final List<LineItemResponse> lineItems;
    private final String paymentMethodLabel;
    private final LocalDate paymentDate;
    private final LocalDateTime issuedAt;
    private final IssuedByResponse issuedBy;
    private final Boolean sealStamped;
    private final Long sealStampLogId;
    private final String pdfStatus;
    private final String pdfDownloadUrl;
    private final Long memberPaymentId;
    private final Long scheduleId;
    private final Boolean isVoided;
    private final LocalDateTime voidedAt;
    private final Long voidedBy;
    private final String voidedReason;
    private final List<WarningResponse> warnings;

    /**
     * 明細行レスポンス。
     */
    @Getter
    @Builder
    public static class LineItemResponse {
        private final Long id;
        private final String description;
        private final BigDecimal amount;
        private final BigDecimal taxRate;
        private final BigDecimal taxAmount;
        private final BigDecimal amountExclTax;
    }

    /**
     * 発行者レスポンス。
     */
    @Getter
    @Builder
    public static class IssuedByResponse {
        private final Long id;
        private final String displayName;
    }

    /**
     * 警告レスポンス。
     */
    @Getter
    @Builder
    public static class WarningResponse {
        private final String code;
        private final String message;
    }
}
