package com.mannschaft.app.receipt.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 領収書プレビューレスポンスDTO（発行前確認・再発行プレビュー用）。
 */
@Getter
@Builder
public class ReceiptPreviewResponse {
    private final String receiptNumber;
    private final String recipientName;
    private final String recipientPostalCode;
    private final String recipientAddress;
    private final String issuerName;
    private final String issuerPostalCode;
    private final String issuerAddress;
    private final Boolean isQualifiedInvoice;
    private final String invoiceRegistrationNumber;
    private final String description;
    private final BigDecimal amount;
    private final BigDecimal taxRate;
    private final BigDecimal taxAmount;
    private final BigDecimal amountExclTax;
    private final LocalDate paymentDate;
    private final Boolean sealStamp;
    private final String sealUserName;
    private final Long reissueSourceId;
    private final String reissueSourceReceiptNumber;
    private final List<ReceiptResponse.WarningResponse> warnings;
}
