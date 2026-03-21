package com.mannschaft.app.ticket.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * チケット統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TicketStatsResponse {

    private final Integer activeBooks;
    private final Long totalRevenue;
    private final Integer totalConsumptionsThisMonth;
    private final Integer expiringWithin30Days;
    private final List<ProductStats> byProduct;

    /**
     * 商品別統計。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ProductStats {
        private final Long productId;
        private final String productName;
        private final Integer activeBooks;
        private final Integer totalSold;
        private final Long revenue;
        private final BigDecimal avgConsumptionRate;
        private final BigDecimal avgDaysToExhaust;
        private final BigDecimal expiryRate;
    }
}
