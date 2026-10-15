package com.mannschaft.app.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 会費領収書レスポンス（F08.9 P8）。
 *
 * <p>Stripe receipt_url が存在する場合はその URL を返す。
 * 税内訳（taxInfo）は TaxPolicy 確定まで null で返す。</p>
 */
public record ReceiptResponse(
        Long memberPaymentId,
        String issuedBy,
        BigDecimal amount,
        String currency,
        LocalDate issuedDate,
        String receiptUrl,
        TaxBreakdownDto taxInfo
) {

    /**
     * 将来の税内訳（現在は null で返す）。
     */
    public record TaxBreakdownDto(
            String taxCategory,
            BigDecimal taxRate,
            BigDecimal grossAmount,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            String registrationNumber
    ) {}
}
