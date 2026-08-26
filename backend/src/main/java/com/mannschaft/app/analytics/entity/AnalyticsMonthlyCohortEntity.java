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
 * 月次コホート分析エンティティ。
 */
@Entity
@Table(name = "analytics_monthly_cohorts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class AnalyticsMonthlyCohortEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate cohortMonth;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private int monthsElapsed;

    @Builder.Default
    @Column(nullable = false)
    private int cohortSize = 0;

    @Builder.Default
    @Column(nullable = false)
    private int retainedUsers = 0;

    @Builder.Default
    @Column(nullable = false)
    private int retainedPaying = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;
}
