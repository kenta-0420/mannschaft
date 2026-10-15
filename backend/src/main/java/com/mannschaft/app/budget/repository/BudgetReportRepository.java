package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.entity.BudgetReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 予算報告書リポジトリ。
 */
public interface BudgetReportRepository extends JpaRepository<BudgetReportEntity, Long> {

    /**
     * 年度IDで報告書を検索する。
     */
    List<BudgetReportEntity> findByFiscalYearId(Long fiscalYearId);

    /**
     * スコープで報告書を検索する。
     */
    List<BudgetReportEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * 生成者で報告書を検索する。
     */
    List<BudgetReportEntity> findByGeneratedBy(Long generatedBy);
}
