package com.mannschaft.app.analytics.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月次スナップショットエンティティ。
 */
@Entity
@Table(name = "analytics_monthly_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnalyticsMonthlySnapshotEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate month;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal mrr = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal arr = BigDecimal.ZERO;

    private BigDecimal arpu;

    private BigDecimal ltv;

    private BigDecimal nrr;

    private BigDecimal quickRatio;

    private BigDecimal paybackMonths;

    @Builder.Default
    private int totalUsers = 0;

    @Builder.Default
    private int activeUsers = 0;

    @Builder.Default
    private int payingUsers = 0;

    @Builder.Default
    private int newUsers = 0;

    @Builder.Default
    private int churnedUsers = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal userChurnRate = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal revenueChurnRate = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal netRevenue = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal adRevenue = BigDecimal.ZERO;

    @Builder.Default
    private boolean reportSent = false;

    private LocalDateTime reportSentAt;

    public void markReportSent() {
        this.reportSent = true;
        this.reportSentAt = LocalDateTime.now();
    }
}
