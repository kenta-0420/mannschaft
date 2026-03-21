package com.mannschaft.app.performance.repository;

import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * パフォーマンス記録リポジトリ。
 */
public interface PerformanceRecordRepository extends JpaRepository<PerformanceRecordEntity, Long> {

    /**
     * 指標×ユーザーの記録一覧を日付降順で取得する。
     */
    List<PerformanceRecordEntity> findByMetricIdAndUserIdOrderByRecordedDateDesc(Long metricId, Long userId);

    /**
     * ユーザーの全記録を日付降順で取得する。
     */
    Page<PerformanceRecordEntity> findByUserIdOrderByRecordedDateDesc(Long userId, Pageable pageable);

    /**
     * 指標×ユーザー×期間の記録を取得する。
     */
    @Query("SELECT r FROM PerformanceRecordEntity r WHERE r.metricId = :metricId AND r.userId = :userId " +
            "AND (:dateFrom IS NULL OR r.recordedDate >= :dateFrom) AND (:dateTo IS NULL OR r.recordedDate <= :dateTo) " +
            "ORDER BY r.recordedDate DESC")
    List<PerformanceRecordEntity> findByMetricIdAndUserIdAndDateRange(
            @Param("metricId") Long metricId, @Param("userId") Long userId,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    /**
     * スケジュール紐付きの記録一覧を取得する。
     */
    List<PerformanceRecordEntity> findByScheduleIdOrderByUserIdAscMetricIdAsc(Long scheduleId);

    /**
     * 活動記録紐付きの記録一覧を取得する。
     */
    List<PerformanceRecordEntity> findByActivityResultIdOrderByUserIdAscMetricIdAsc(Long activityResultId);

    /**
     * 指標の全記録を日付降順で取得する。
     */
    List<PerformanceRecordEntity> findByMetricIdOrderByRecordedDateDesc(Long metricId);

    /**
     * 指標IDリストに紐づく記録を期間フィルタ付きで取得する。
     */
    @Query("SELECT r FROM PerformanceRecordEntity r WHERE r.metricId IN :metricIds " +
            "AND (:dateFrom IS NULL OR r.recordedDate >= :dateFrom) AND (:dateTo IS NULL OR r.recordedDate <= :dateTo)")
    List<PerformanceRecordEntity> findByMetricIdsAndDateRange(
            @Param("metricIds") List<Long> metricIds,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    /**
     * 指標×ユーザー×月の記録を集計用に取得する。
     */
    @Query("SELECT r FROM PerformanceRecordEntity r WHERE r.metricId = :metricId AND r.userId = :userId " +
            "AND FUNCTION('DATE_FORMAT', r.recordedDate, '%Y-%m') = :yearMonth ORDER BY r.recordedDate DESC")
    List<PerformanceRecordEntity> findByMetricIdAndUserIdAndYearMonth(
            @Param("metricId") Long metricId, @Param("userId") Long userId, @Param("yearMonth") String yearMonth);

    /**
     * 前日の記録に含まれるユニークな指標ID×ユーザーIDの組み合わせを取得する。
     */
    @Query("SELECT DISTINCT r.metricId, r.userId FROM PerformanceRecordEntity r WHERE r.recordedDate = :date")
    List<Object[]> findDistinctMetricUserByDate(@Param("date") LocalDate date);

    /**
     * CSVエクスポート用: フィルタ付きで記録を取得する。
     */
    @Query("SELECT r FROM PerformanceRecordEntity r JOIN PerformanceMetricEntity m ON r.metricId = m.id " +
            "WHERE m.teamId = :teamId " +
            "AND (:metricId IS NULL OR r.metricId = :metricId) " +
            "AND (:userId IS NULL OR r.userId = :userId) " +
            "AND (:dateFrom IS NULL OR r.recordedDate >= :dateFrom) " +
            "AND (:dateTo IS NULL OR r.recordedDate <= :dateTo) " +
            "ORDER BY r.recordedDate DESC, r.userId ASC")
    List<PerformanceRecordEntity> findForExport(
            @Param("teamId") Long teamId,
            @Param("metricId") Long metricId, @Param("userId") Long userId,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    /**
     * CSVエクスポート用: フィルタ付きで記録件数をカウントする。
     */
    @Query("SELECT COUNT(r) FROM PerformanceRecordEntity r JOIN PerformanceMetricEntity m ON r.metricId = m.id " +
            "WHERE m.teamId = :teamId " +
            "AND (:metricId IS NULL OR r.metricId = :metricId) " +
            "AND (:userId IS NULL OR r.userId = :userId) " +
            "AND (:dateFrom IS NULL OR r.recordedDate >= :dateFrom) " +
            "AND (:dateTo IS NULL OR r.recordedDate <= :dateTo)")
    long countForExport(
            @Param("teamId") Long teamId,
            @Param("metricId") Long metricId, @Param("userId") Long userId,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    /**
     * ユーザーの指標ごとの記録を期間フィルタ付きで取得する。
     */
    @Query("SELECT r FROM PerformanceRecordEntity r WHERE r.userId = :userId " +
            "AND r.metricId IN :metricIds " +
            "AND (:dateFrom IS NULL OR r.recordedDate >= :dateFrom) " +
            "AND (:dateTo IS NULL OR r.recordedDate <= :dateTo) " +
            "ORDER BY r.recordedDate DESC")
    List<PerformanceRecordEntity> findByUserIdAndMetricIdsAndDateRange(
            @Param("userId") Long userId, @Param("metricIds") List<Long> metricIds,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    /**
     * 指標×ユーザーの最大値を取得する。
     */
    @Query("SELECT MAX(r.value) FROM PerformanceRecordEntity r WHERE r.metricId = :metricId AND r.userId = :userId")
    Optional<BigDecimal> findMaxValueByMetricIdAndUserId(@Param("metricId") Long metricId, @Param("userId") Long userId);

    /**
     * 指標×ユーザーの最小値を取得する。
     */
    @Query("SELECT MIN(r.value) FROM PerformanceRecordEntity r WHERE r.metricId = :metricId AND r.userId = :userId")
    Optional<BigDecimal> findMinValueByMetricIdAndUserId(@Param("metricId") Long metricId, @Param("userId") Long userId);
}
