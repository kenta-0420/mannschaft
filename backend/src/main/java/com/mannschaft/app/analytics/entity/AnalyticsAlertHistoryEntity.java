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
import java.time.LocalDateTime;

/**
 * アラート発火履歴エンティティ。
 */
@Entity
@Table(name = "analytics_alert_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnalyticsAlertHistoryEntity extends BaseEntity {

    @Column(nullable = false)
    private Long ruleId;

    @Column(nullable = false)
    private LocalDateTime triggeredAt;

    @Column(nullable = false)
    private BigDecimal metricValue;

    @Column(nullable = false)
    private BigDecimal thresholdValue;

    private BigDecimal comparisonValue;

    private BigDecimal changePct;

    @Builder.Default
    private boolean notified = false;
}
