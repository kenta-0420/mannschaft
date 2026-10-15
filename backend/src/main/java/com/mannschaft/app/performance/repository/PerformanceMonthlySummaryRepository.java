package com.mannschaft.app.performance.repository;

import com.mannschaft.app.performance.entity.PerformanceMonthlySummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * パフォーマンス月次集計サマリーリポジトリ。
 */
public interface PerformanceMonthlySummaryRepository extends JpaRepository<PerformanceMonthlySummaryEntity, Long> {

    /**
     * 指標×ユーザー×月でサマリーを取得する。
     */
    Optional<PerformanceMonthlySummaryEntity> findByMetricIdAndUserIdAndYearMonth(
            Long metricId, Long userId, String yearMonth);

    /**
     * 指標×ユーザーのサマリーを月降順で取得する。
     */
    List<PerformanceMonthlySummaryEntity> findByMetricIdAndUserIdOrderByYearMonthDesc(
            Long metricId, Long userId);

    /**
     * ユーザーのサマリーを月降順で取得する。
     */
    List<PerformanceMonthlySummaryEntity> findByUserIdOrderByYearMonthDesc(Long userId);

    /**
     * 指標のサマリーを取得する（チーム統計用）。
     */
    List<PerformanceMonthlySummaryEntity> findByMetricIdOrderByYearMonthDesc(Long metricId);
}
