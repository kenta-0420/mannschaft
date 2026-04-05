package com.mannschaft.app.budget.entity;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 予算取引エンティティ。
 */
@Entity
@Table(name = "budget_transactions")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BudgetTransactionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long fiscalYearId;

    @Column(nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BudgetTransactionType transactionType;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDate transactionDate;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 30)
    private String paymentMethod;

    @Column(length = 100)
    private String referenceNumber;

    @Column(length = 200)
    private String counterparty;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAutoRecorded = false;

    @Column(length = 50)
    private String sourceType;

    private Long sourceId;

    private Long reversalOfId;

    private Long workflowRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BudgetApprovalStatus approvalStatus = BudgetApprovalStatus.APPROVED;

    @Column(nullable = false)
    private Long recordedBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 取引を承認する。
     */
    public void approve() {
        this.approvalStatus = BudgetApprovalStatus.APPROVED;
    }

    /**
     * 取引を却下する。
     */
    public void reject() {
        this.approvalStatus = BudgetApprovalStatus.REJECTED;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
