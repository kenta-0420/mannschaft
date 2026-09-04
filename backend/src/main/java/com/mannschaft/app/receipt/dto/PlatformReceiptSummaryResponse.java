package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 運営領収書の一覧要素（F08.12 §4.1）。
 */
@Getter
@RequiredArgsConstructor
public class PlatformReceiptSummaryResponse {

    private final Long id;
    private final String receiptNumber;
    private final String sourceType;
    /** 元データ ID の文字列表現（BIGINT 系は 10 進、UUID 系は小文字 36 文字）。 */
    private final String sourceRef;
    /** 宛名（復号済み）。 */
    private final String recipientName;
    private final BigDecimal amount;
    private final BigDecimal taxAmount;
    private final BigDecimal amountExclTax;
    /** 発行時点の登録状態のスナップショット。 */
    private final Boolean isQualifiedInvoice;
    /** 未登録期に発行したものは null。 */
    private final String invoiceRegistrationNumber;
    private final LocalDateTime issuedAt;
    private final LocalDateTime voidedAt;
    /** {@code READY} / {@code GENERATING} / {@code FAILED}。列を読む（NULL 判定からの導出はしない）。 */
    private final String pdfStatus;
}
