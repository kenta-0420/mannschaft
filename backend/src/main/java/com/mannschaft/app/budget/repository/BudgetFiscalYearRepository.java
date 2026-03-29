package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.BudgetFiscalYearStatus;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 予算年度リポジトリ。
 */
public interface BudgetFiscalYearRepository extends JpaRepository<BudgetFiscalYearEntity, Long> {

    /**
     * スコープとステータスで年度を検索する。
     */
    List<BudgetFiscalYearEntity> findByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, BudgetFiscalYearStatus status);

    /**
     * スコープで年度を検索する。
     */
    List<BudgetFiscalYearEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /**
     * スコープと年度名で年度を検索する。
     */
    Optional<BudgetFiscalYearEntity> findByScopeTypeAndScopeIdAndName(String scopeType, Long scopeId, String name);

    /**
     * スコープで期間が重複する年度が存在するか確認する。
     */
    boolean existsByScopeTypeAndScopeIdAndStartDateLessThanAndEndDateGreaterThan(String scopeType, Long scopeId, LocalDate endDate, LocalDate startDate);
}
