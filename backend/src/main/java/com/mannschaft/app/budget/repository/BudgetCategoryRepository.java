package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.BudgetCategoryType;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 予算費目リポジトリ。
 */
public interface BudgetCategoryRepository extends JpaRepository<BudgetCategoryEntity, Long> {

    /**
     * 年度IDで費目を検索する。
     */
    List<BudgetCategoryEntity> findByFiscalYearId(Long fiscalYearId);

    /**
     * 親費目IDで子費目を検索する。
     */
    List<BudgetCategoryEntity> findByParentId(Long parentId);

    /**
     * 年度ID・親ID・名前で費目を検索する。
     */
    Optional<BudgetCategoryEntity> findByFiscalYearIdAndParentIdAndName(Long fiscalYearId, Long parentId, String name);

    /**
     * 年度IDで費目数をカウントする。
     */
    long countByFiscalYearId(Long fiscalYearId);

    /**
     * 年度IDと費目種別で費目を検索する。
     */
    List<BudgetCategoryEntity> findByFiscalYearIdAndCategoryType(Long fiscalYearId, BudgetCategoryType categoryType);
}
