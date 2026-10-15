package com.mannschaft.app.analytics.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
public class AnalyticsDailyAdsEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate date;

    @Builder.Default
    @Column(nullable = false)
    private int impressions = 0;

    @Builder.Default
    @Column(nullable = false)
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
