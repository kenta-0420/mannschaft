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
 * 月次コホート分析エンティティ。
 */
@Entity
@Table(name = "analytics_monthly_cohorts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnalyticsMonthlyCohortEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate cohortMonth;

    @Column(nullable = false)
    private int monthsElapsed;

    @Builder.Default
    private int cohortSize = 0;

    @Builder.Default
    private int retainedUsers = 0;

    @Builder.Default
    private int retainedPaying = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;
}
