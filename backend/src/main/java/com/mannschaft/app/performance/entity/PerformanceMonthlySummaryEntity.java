package com.mannschaft.app.performance.entity;

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

/**
 * パフォーマンス月次集計サマリーエンティティ。月次集計のサマリーを管理する。
 */
@Entity
@Table(name = "performance_monthly_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PerformanceMonthlySummaryEntity extends BaseEntity {

    @Column(nullable = false)
    private Long metricId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 7)
    private String yearMonth;

    @Column(nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal sumValue = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal avgValue = BigDecimal.ZERO;

    @Column(precision = 15, scale = 4)
    private BigDecimal maxValue;

    @Column(precision = 15, scale = 4)
    private BigDecimal minValue;

    @Column(precision = 15, scale = 4)
    private BigDecimal latestValue;

    @Column(nullable = false)
    @Builder.Default
    private Integer recordCount = 0;

    /**
     * サマリーを更新する。
     */
    public void updateSummary(BigDecimal sumValue, BigDecimal avgValue, BigDecimal maxValue,
                              BigDecimal minValue, BigDecimal latestValue, int recordCount) {
        this.sumValue = sumValue;
        this.avgValue = avgValue;
        this.maxValue = maxValue;
        this.minValue = minValue;
        this.latestValue = latestValue;
        this.recordCount = recordCount;
    }
}
