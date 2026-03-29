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
 * 予算配分エンティティ。
 */
@Entity
@Table(name = "budget_allocations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BudgetAllocationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long fiscalYearId;

    @Column(nullable = false)
    private Long categoryId;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Column(length = 500)
    private String note;

    @Version
    private Long version;

    /**
     * 予算配分額を更新する。
     */
    public void updateAmount(BigDecimal amount, String note) {
        this.amount = amount;
        this.note = note;
    }
}
