package com.mannschaft.app.repairplan.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * v_repair_fund_balance ビューのマッピング（読み取り専用）。
 *
 * <p>scope_type + scope_id でフィルタしてシミュレーションの初期残高を取得する。
 * ビュー定義: scope_type / scope_id / balance / as_of_date の 4 カラム。</p>
 */
@Entity
@Immutable
@Subselect("SELECT scope_type, scope_id, " +
        "SUM(CASE WHEN transaction_type = 'INCOME' THEN amount ELSE -amount END) AS balance, " +
        "MAX(transaction_date) AS as_of_date " +
        "FROM budget_transactions bt " +
        "INNER JOIN budget_categories bc ON bt.category_id = bc.id " +
        "WHERE bt.deleted_at IS NULL AND bt.approval_status = 'APPROVED' " +
        "AND bc.name LIKE '%修繕積立金%' " +
        "GROUP BY bt.scope_type, bt.scope_id")
public class RepairFundBalanceView {

    /**
     * 複合ビューのため scope_type+scope_id が実質的な複合 PK だが、
     * JPA の @Id は単一カラムが必要なため scope_id を識別子に使用する。
     * findByScopeTypeAndScopeId クエリで正しく絞り込む。
     */
    @Id
    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "scope_type")
    private String scopeType;

    /** 修繕積立金の現在残高（INCOME - EXPENSE の累計）。 */
    @Column(name = "balance")
    private BigDecimal balance;

    @Column(name = "as_of_date")
    private LocalDate asOfDate;

    // --- getters only（Immutable） ---

    public Long getScopeId() {
        return scopeId;
    }

    public String getScopeType() {
        return scopeType;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }
}
