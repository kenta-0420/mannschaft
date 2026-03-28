package com.mannschaft.app.analytics.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * KPIスナップショットレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class KpiSnapshotResponse {

    private final String month;
    private final BigDecimal mrr;
    private final BigDecimal arr;
    private final BigDecimal arpu;
    private final BigDecimal ltv;
    private final BigDecimal nrr;
    private final BigDecimal quickRatio;
    private final BigDecimal paybackMonths;
    private final int totalUsers;
    private final int activeUsers;
    private final int payingUsers;
    private final int newUsers;
    private final int churnedUsers;
    private final BigDecimal userChurnRate;
    private final BigDecimal revenueChurnRate;
    private final BigDecimal netRevenue;
    private final BigDecimal adRevenue;
    private final boolean reportSent;
    private final LocalDateTime reportSentAt;
}
