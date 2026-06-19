package com.mannschaft.app.advertising.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 広告日次統計エンティティ。
 */
@Entity
@Table(name = "ad_daily_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class AdDailyStatsEntity extends BaseEntity {

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private Long adId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    @SuperBuilder.Default
    private long impressions = 0;

    @Column(nullable = false)
    @SuperBuilder.Default
    private long clicks = 0;

    @Column(nullable = false)
    @SuperBuilder.Default
    private BigDecimal cost = BigDecimal.ZERO;
}
