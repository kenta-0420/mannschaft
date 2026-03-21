package com.mannschaft.app.chart.entity;

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
 * 身体チャートマーク情報エンティティ。
 */
@Entity
@Table(name = "chart_body_marks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChartBodyMarkEntity extends BaseEntity {

    @Column(nullable = false)
    private Long chartRecordId;

    @Column(nullable = false, length = 20)
    private String bodyPart;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal xPosition;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal yPosition;

    @Column(nullable = false, length = 20)
    private String markType;

    @Column(nullable = false)
    private Integer severity;

    @Column(length = 300)
    private String note;
}
