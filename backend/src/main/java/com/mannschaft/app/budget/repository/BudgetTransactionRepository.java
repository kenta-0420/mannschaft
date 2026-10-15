package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/**
 * 予算取引リポジトリ。
 */
public interface BudgetTransactionRepository extends JpaRepository<BudgetTransactionEntity, Long> {

    /**
     * 年度IDで取引を検索する。
     */
    List<BudgetTransactionEntity> findByFiscalYearId(Long fiscalYearId);

    /**
     * 年度IDで取引をページネーション検索する。
     */
    Page<BudgetTransactionEntity> findByFiscalYearId(Long fiscalYearId, Pageable pageable);

    /**
     * 費目IDで取引を検索する。
     */
    List<BudgetTransactionEntity> findByCategoryId(Long categoryId);

    /**
     * スコープで取引を検索する。
     */
    List<BudgetTransactionEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * 取引日の範囲で取引を検索する。
     */
    List<BudgetTransactionEntity> findByFiscalYearIdAndTransactionDateBetween(Long fiscalYearId, LocalDate start, LocalDate end);

    /**
     * 承認ステータスで取引を検索する。
     */
    List<BudgetTransactionEntity> findByFiscalYearIdAndApprovalStatus(Long fiscalYearId, BudgetApprovalStatus approvalStatus);

    /**
     * ソースで取引を検索する。
     */
    List<BudgetTransactionEntity> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    /**
     * 記録者で取引を検索する。
     */
    List<BudgetTransactionEntity> findByRecordedBy(Long recordedBy);

    /**
     * 年度IDで取引数をカウントする。
     */
    long countByFiscalYearId(Long fiscalYearId);

    /**
     * 年度IDと費目IDで承認済み取引を検索する。
     */
    List<BudgetTransactionEntity> findByFiscalYearIdAndCategoryIdAndApprovalStatus(Long fiscalYearId, Long categoryId, BudgetApprovalStatus approvalStatus);

    /**
     * 同一 (source_type, source_id, transaction_date) の取引が存在するかを判定する。
     *
     * <p>F08.7 Phase 9-δ 月次締めの冪等性保証用:
     * {@code source_type='SHIFT_BUDGET_MONTHLY'} かつ {@code source_id=allocation_id} かつ
     * {@code transaction_date=lastDayOfMonth} のレコード既存をチェックし、
     * 二重仕訳を防ぐ。設計書 §4.6 / §6.1 #11 参照。</p>
     */
    boolean existsBySourceTypeAndSourceIdAndTransactionDate(
            String sourceType, Long sourceId, LocalDate transactionDate);
}
