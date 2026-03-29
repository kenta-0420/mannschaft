package com.mannschaft.app.budget.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 予算設定エンティティ。
 */
@Entity
@Table(name = "budget_configs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BudgetConfigEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(precision = 12, scale = 0)
    private BigDecimal approvalThreshold;

    private Long workflowTemplateId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoRecordPayments = true;

    private Long defaultIncomeCategoryId;

    @Column(nullable = false)
    @Builder.Default
    private Short budgetWarningThreshold = 80;

    @Column(nullable = false)
    @Builder.Default
    private Short budgetCriticalThreshold = 95;

    @Version
    private Long version;

    /**
     * 予算設定を更新する。
     */
    public void update(BigDecimal approvalThreshold, Long workflowTemplateId,
                       Boolean autoRecordPayments, Long defaultIncomeCategoryId,
                       Short budgetWarningThreshold, Short budgetCriticalThreshold) {
        this.approvalThreshold = approvalThreshold;
        this.workflowTemplateId = workflowTemplateId;
        this.autoRecordPayments = autoRecordPayments;
        this.defaultIncomeCategoryId = defaultIncomeCategoryId;
        this.budgetWarningThreshold = budgetWarningThreshold;
        this.budgetCriticalThreshold = budgetCriticalThreshold;
    }
}
