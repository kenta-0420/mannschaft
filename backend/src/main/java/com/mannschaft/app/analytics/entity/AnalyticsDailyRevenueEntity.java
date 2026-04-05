package com.mannschaft.app.analytics.entity;

import com.mannschaft.app.analytics.RevenueSource;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日次売上集計エンティティ。
 */
@Entity
@Table(name = "analytics_daily_revenue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnalyticsDailyRevenueEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RevenueSource revenueSource;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal grossRevenue = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal netRevenue = BigDecimal.ZERO;

    @Builder.Default
    private int transactionCount = 0;
}
