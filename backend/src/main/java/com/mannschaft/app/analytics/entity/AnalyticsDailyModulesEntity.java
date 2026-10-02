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
 * 日次モジュール利用統計エンティティ。
 */
@Entity
@Table(name = "analytics_daily_modules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class AnalyticsDailyModulesEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private Long moduleId;

    @Builder.Default
    @Column(nullable = false)
    private int activeTeams = 0;

    @Builder.Default
    @Column(nullable = false)
    private int newActivations = 0;

    @Builder.Default
    @Column(nullable = false)
    private int deactivations = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;
}
