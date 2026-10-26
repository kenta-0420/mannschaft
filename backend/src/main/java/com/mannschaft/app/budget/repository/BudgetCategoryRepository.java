package com.mannschaft.app.budget.repository;

import com.mannschaft.app.budget.BudgetCategoryType;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 予算費目リポジトリ。
 */
public interface BudgetCategoryRepository extends JpaRepository<BudgetCategoryEntity, Long> {

    /**
     * 認可済み年度の履歴表示専用。SQLRestrictionを迂回するがEntityを返さず、
     * 削除済みEntityの一次キャッシュ混入や更新を避ける。
     */
    interface HistoricalCategory {
        Long getId();
        String getName();
        String getCategoryType();
        Integer getDeletedFlag();
    }

    @Query(value = "SELECT id, name, category_type AS categoryType, "
            + "CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deletedFlag "
            + "FROM budget_categories WHERE fiscal_year_id = :fiscalYearId ORDER BY sort_order, id", nativeQuery = true)
    List<HistoricalCategory> findHistoricalByFiscalYearId(@Param("fiscalYearId") Long fiscalYearId);

    @Query(value = "SELECT id, name, category_type AS categoryType, "
            + "CASE WHEN deleted_at IS NULL THEN 0 ELSE 1 END AS deletedFlag "
            + "FROM budget_categories WHERE fiscal_year_id = :fiscalYearId AND id = :categoryId", nativeQuery = true)
    Optional<HistoricalCategory> findHistoricalByFiscalYearIdAndId(
            @Param("fiscalYearId") Long fiscalYearId, @Param("categoryId") Long categoryId);

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
