package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

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
    /**
     * 発行日時。<b>起きた瞬間</b>なので {@link Instant} で公開する（時刻方針 4 章）。
     * 元の {@code receipts.issued_at} は既存の {@code LocalDateTime} 列であるため、
     * 変換には方針 §7 が「既存の唯一の正」と定める
     * {@code UserZoneLocalDateTimeParser.SERVER_ZONE} を用いる（ゾーンの直書きはしない）。
     */
    private final Instant issuedAt;
    private final Instant voidedAt;
    /** {@code READY} / {@code GENERATING} / {@code FAILED}。列を読む（NULL 判定からの導出はしない）。 */
    private final String pdfStatus;
}
