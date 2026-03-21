package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 支払いサマリーレスポンスDTO（ADMIN ダッシュボード用）。
 */
@Getter
@RequiredArgsConstructor
public class PaymentSummaryResponse {

    private final Long teamId;
    private final Long organizationId;
    private final int totalMembers;
    private final List<ItemSummary> items;

    /**
     * 項目別の集計情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ItemSummary {
        private final Long paymentItemId;
        private final String name;
        private final String type;
        private final BigDecimal amount;
        private final String currency;
        private final long paidCount;
        private final long unpaidCount;
        private final BigDecimal totalCollected;
        private final Boolean isActive;
        private final Short displayOrder;
    }
}
