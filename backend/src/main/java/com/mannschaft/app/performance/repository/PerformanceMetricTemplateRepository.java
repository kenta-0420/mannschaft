package com.mannschaft.app.performance.repository;

import com.mannschaft.app.performance.entity.PerformanceMetricTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * パフォーマンス指標テンプレートリポジトリ。
 */
public interface PerformanceMetricTemplateRepository extends JpaRepository<PerformanceMetricTemplateEntity, Long> {

    /**
     * スポーツカテゴリ別にテンプレートを取得する。
     */
    List<PerformanceMetricTemplateEntity> findBySportCategoryOrderBySortOrderAsc(String sportCategory);

    /**
     * 全テンプレートをスポーツカテゴリ順→表示順で取得する。
     */
    List<PerformanceMetricTemplateEntity> findAllByOrderBySportCategoryAscSortOrderAsc();

    /**
     * ユニークなスポーツカテゴリ一覧を取得する。
     */
    @Query("SELECT DISTINCT t.sportCategory FROM PerformanceMetricTemplateEntity t ORDER BY t.sportCategory")
    List<String> findDistinctSportCategories();
}
