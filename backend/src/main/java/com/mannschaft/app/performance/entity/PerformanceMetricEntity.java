package com.mannschaft.app.performance.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.performance.AggregationType;
import com.mannschaft.app.performance.MetricDataType;
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

/**
 * パフォーマンス指標エンティティ。チームごとのパフォーマンス指標定義を管理する。
 */
@Entity
@Table(name = "performance_metrics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PerformanceMetricEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private MetricDataType dataType = MetricDataType.DECIMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private AggregationType aggregationType = AggregationType.SUM;

    @Column(length = 500)
    private String description;

    @Column(length = 50)
    private String groupName;

    @Column(precision = 15, scale = 4)
    private BigDecimal targetValue;

    @Column(nullable = false)
    @Builder.Default
    private Boolean targetAchievedNotified = false;

    @Column(precision = 15, scale = 4)
    private BigDecimal minValue;

    @Column(precision = 15, scale = 4)
    private BigDecimal maxValue;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isVisibleToMembers = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSelfRecordable = false;

    private Long linkedActivityFieldId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 指標定義を更新する。
     */
    public void update(String name, String unit, MetricDataType dataType, AggregationType aggregationType,
                       String description, String groupName, BigDecimal targetValue,
                       BigDecimal minValue, BigDecimal maxValue, Integer sortOrder,
                       Boolean isVisibleToMembers, Boolean isSelfRecordable, Long linkedActivityFieldId) {
        this.name = name;
        this.unit = unit;
        this.dataType = dataType;
        this.aggregationType = aggregationType;
        this.description = description;
        this.groupName = groupName;
        if (targetValue != null && (this.targetValue == null || this.targetValue.compareTo(targetValue) != 0)) {
            this.targetAchievedNotified = false;
        }
        this.targetValue = targetValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.sortOrder = sortOrder;
        this.isVisibleToMembers = isVisibleToMembers;
        this.isSelfRecordable = isSelfRecordable;
        this.linkedActivityFieldId = linkedActivityFieldId;
    }

    /**
     * 指標を無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 表示順を更新する。
     */
    public void updateSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /**
     * 目標達成通知済みに設定する。
     */
    public void markTargetAchievedNotified() {
        this.targetAchievedNotified = true;
    }
}
