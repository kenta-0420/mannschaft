package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 発行者設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class IssuerSettingsResponse {
    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String issuerName;
    private final String postalCode;
    private final String address;
    private final String phone;
    private final Boolean isQualifiedInvoicer;
    private final String invoiceRegistrationNumber;
    private final Long defaultSealUserId;
    private final String defaultSealVariant;
    private final String receiptNoteTemplate;
    private final String logoStorageKey;
    private final String customFooter;
    private final Integer nextReceiptNumber;
    private final String receiptNumberPrefix;
    private final Integer fiscalYearStartMonth;
    private final Boolean autoResetNumber;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
