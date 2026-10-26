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
import java.time.LocalDateTime;

/**
 * アラート発火履歴エンティティ。
 */
@Entity
@Table(name = "analytics_alert_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
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
    @Column(nullable = false)
    private boolean notified = false;

    /**
     * 通知済みフラグを立てる。
     * managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する。
     * （toBuilder().build() は継承フィールド id を引き継がず INSERT 化するため使用しない）
     */
    public void markNotified() {
        this.notified = true;
    }
}
