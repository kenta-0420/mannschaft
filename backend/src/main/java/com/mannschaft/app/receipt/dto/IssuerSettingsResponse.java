package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 発行者設定レスポンスDTO。
 *
 * <p>{@code logoUrl} は {@code logoStorageKey} から生成した署名付き GET URL である
 * （F08.4 §9.1.1 D-8）。ロゴ未設定なら {@code null}。FE は表示にこちらだけを使い、
 * {@code logoStorageKey} から URL を組み立ててはならない。</p>
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
    private final String logoUrl;
    private final String customFooter;
    private final Integer nextReceiptNumber;
    private final String receiptNumberPrefix;
    private final Integer fiscalYearStartMonth;
    private final Boolean autoResetNumber;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
