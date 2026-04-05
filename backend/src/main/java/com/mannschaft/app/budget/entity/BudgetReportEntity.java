package com.mannschaft.app.budget.entity;

import com.mannschaft.app.budget.BudgetReportStatus;
import com.mannschaft.app.budget.BudgetReportType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 予算報告書エンティティ。
 */
@Entity
@Table(name = "budget_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BudgetReportEntity extends BaseEntity {

    @Column(nullable = false)
    private Long fiscalYearId;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BudgetReportType reportType;

    @Column(nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private LocalDate periodEnd;

    @Column(length = 500)
    private String fileKey;

    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BudgetReportStatus status = BudgetReportStatus.GENERATING;

    @Column(nullable = false)
    private Long generatedBy;

    private LocalDateTime generatedAt;

    /**
     * 報告書の生成完了を記録する。
     */
    public void markCompleted(String fileKey, Long fileSize) {
        this.fileKey = fileKey;
        this.fileSize = fileSize;
        this.status = BudgetReportStatus.COMPLETED;
        this.generatedAt = LocalDateTime.now();
    }

    /**
     * 報告書の生成失敗を記録する。
     */
    public void markFailed() {
        this.status = BudgetReportStatus.FAILED;
    }
}
