package com.mannschaft.app.budget.entity;

import com.mannschaft.app.budget.BudgetFiscalYearStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 予算年度エンティティ。
 */
@Entity
@Table(name = "budget_fiscal_years")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BudgetFiscalYearEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BudgetFiscalYearStatus status = BudgetFiscalYearStatus.OPEN;

    @Column(nullable = false)
    private Long createdBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 年度を締める。
     */
    public void close() {
        this.status = BudgetFiscalYearStatus.CLOSED;
    }

    /**
     * 年度を再開する。
     */
    public void reopen() {
        this.status = BudgetFiscalYearStatus.OPEN;
    }

    /**
     * 会計年度の基本情報を更新する。
     * 管理対象（managed）エンティティを直接ミューテートすることで、
     * 主キー id を保持したまま UPDATE 文が発行されることを保証する。
     * （toBuilder().build() による再構築は継承フィールド id を引き継がず INSERT 化するため使用しない）
     */
    public void applyUpdate(String name, LocalDate startDate, LocalDate endDate) {
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
