package com.mannschaft.app.analytics.entity;

import com.mannschaft.app.analytics.AlertCondition;
import com.mannschaft.app.analytics.AlertMetric;
import com.mannschaft.app.analytics.ComparisonPeriod;
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
import java.time.LocalDateTime;

/**
 * アラートルールエンティティ。
 */
@Entity
@Table(name = "analytics_alert_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnalyticsAlertRuleEntity extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AlertMetric metric;

    @Column(name = "`condition`", nullable = false)
    @Enumerated(EnumType.STRING)
    private AlertCondition condition;

    @Column(nullable = false)
    private BigDecimal threshold;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ComparisonPeriod comparisonPeriod;

    @Builder.Default
    private boolean enabled = true;

    @Column(nullable = false, columnDefinition = "JSON")
    private String notifyChannels;

    @Column(columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private int consecutiveTriggers = 1;

    @Builder.Default
    private int cooldownHours = 24;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    public void updateFrom(String name, AlertMetric metric, AlertCondition condition,
                           BigDecimal threshold, ComparisonPeriod comparisonPeriod,
                           Boolean enabled, String notifyChannels,
                           Integer consecutiveTriggers, Integer cooldownHours) {
        if (name != null) this.name = name;
        if (metric != null) this.metric = metric;
        if (condition != null) this.condition = condition;
        if (threshold != null) this.threshold = threshold;
        if (comparisonPeriod != null) this.comparisonPeriod = comparisonPeriod;
        if (enabled != null) this.enabled = enabled;
        if (notifyChannels != null) this.notifyChannels = notifyChannels;
        if (consecutiveTriggers != null) this.consecutiveTriggers = consecutiveTriggers;
        if (cooldownHours != null) this.cooldownHours = cooldownHours;
    }
}
