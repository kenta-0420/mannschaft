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

import java.time.LocalDate;

/**
 * カラー・薬剤レシピエンティティ。
 */
@Entity
@Table(name = "chart_formulas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChartFormulaEntity extends BaseEntity {

    @Column(nullable = false)
    private Long chartRecordId;

    @Column(nullable = false, length = 200)
    private String productName;

    @Column(length = 100)
    private String ratio;

    private Integer processingTimeMinutes;

    @Column(length = 50)
    private String temperature;

    private LocalDate patchTestDate;

    @Column(length = 20)
    private String patchTestResult;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * 薬剤レシピを更新する。
     */
    public void update(String productName, String ratio, Integer processingTimeMinutes,
                       String temperature, LocalDate patchTestDate, String patchTestResult,
                       String note, Integer sortOrder) {
        this.productName = productName;
        this.ratio = ratio;
        this.processingTimeMinutes = processingTimeMinutes;
        this.temperature = temperature;
        this.patchTestDate = patchTestDate;
        this.patchTestResult = patchTestResult;
        this.note = note;
        this.sortOrder = sortOrder;
    }
}
