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

/**
 * 日次広告統計エンティティ。
 */
@Entity
@Table(name = "analytics_daily_ads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnalyticsDailyAdsEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate date;

    @Builder.Default
    private int impressions = 0;

    @Builder.Default
    private int clicks = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal ctr = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal adRevenue = BigDecimal.ZERO;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal ecpm = BigDecimal.ZERO;
}
