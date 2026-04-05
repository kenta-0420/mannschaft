package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.RevenueSource;
import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 日次売上集計リポジトリ。
 */
public interface AnalyticsDailyRevenueRepository extends JpaRepository<AnalyticsDailyRevenueEntity, Long> {

    List<AnalyticsDailyRevenueEntity> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);

    List<AnalyticsDailyRevenueEntity> findByDateBetweenAndRevenueSourceOrderByDateAsc(
            LocalDate from, LocalDate to, RevenueSource source);

    @Query("SELECT a FROM AnalyticsDailyRevenueEntity a WHERE a.date = :date")
    List<AnalyticsDailyRevenueEntity> findByDate(@Param("date") LocalDate date);

    @Query("SELECT MAX(a.date) FROM AnalyticsDailyRevenueEntity a")
    Optional<LocalDate> findLatestDate();

    Optional<AnalyticsDailyRevenueEntity> findByDateAndRevenueSource(LocalDate date, RevenueSource revenueSource);

    void deleteByDate(LocalDate date);
}
