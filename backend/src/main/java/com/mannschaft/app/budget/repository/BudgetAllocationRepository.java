package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.entity.BudgetAllocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 予算配分リポジトリ。
 */
public interface BudgetAllocationRepository extends JpaRepository<BudgetAllocationEntity, Long> {

    /**
     * 年度IDと費目IDで配分を検索する。
     */
    Optional<BudgetAllocationEntity> findByFiscalYearIdAndCategoryId(Long fiscalYearId, Long categoryId);

    /**
     * 年度IDで配分を検索する。
     */
    List<BudgetAllocationEntity> findByFiscalYearId(Long fiscalYearId);
}
