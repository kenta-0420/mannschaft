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
}
