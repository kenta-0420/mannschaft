package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.entity.AnalyticsMonthlyCohortEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * 月次コホート分析リポジトリ。
 */
public interface AnalyticsMonthlyCohortRepository extends JpaRepository<AnalyticsMonthlyCohortEntity, Long> {

    List<AnalyticsMonthlyCohortEntity> findByCohortMonthBetweenOrderByCohortMonthAscMonthsElapsedAsc(
            LocalDate from, LocalDate to);

    List<AnalyticsMonthlyCohortEntity> findByCohortMonth(LocalDate cohortMonth);
}
