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
 * 日次モジュール利用統計エンティティ。
 */
@Entity
@Table(name = "analytics_daily_modules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnalyticsDailyModulesEntity extends BaseEntity {

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private Long moduleId;

    @Builder.Default
    private int activeTeams = 0;

    @Builder.Default
    private int newActivations = 0;

    @Builder.Default
    private int deactivations = 0;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;
}
