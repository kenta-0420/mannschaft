package com.mannschaft.app.receipt.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一括操作結果レスポンスDTO。
 */
@Getter
@Builder
public class BulkResultResponse {
    private final Integer issuedCount;
    private final Integer skippedCount;
    private final List<IssuedReceipt> receipts;

    /**
     * 発行された領収書の要約。
     */
    @Getter
    @Builder
    public static class IssuedReceipt {
        private final Long id;
        private final String receiptNumber;
        private final String recipientName;
        private final BigDecimal amount;
    }
}
