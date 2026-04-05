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
 * パフォーマンス指標テンプレートエンティティ。スポーツ別の指標テンプレートを管理する。
 */
@Entity
@Table(name = "performance_metric_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PerformanceMetricTemplateEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String sportCategory;

    @Column(length = 50)
    private String groupName;

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

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(precision = 15, scale = 4)
    private BigDecimal minValue;

    @Column(precision = 15, scale = 4)
    private BigDecimal maxValue;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSelfRecordable = false;
}
