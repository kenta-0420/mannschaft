package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.entity.AnalyticsDailyModulesEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * 日次モジュール利用統計リポジトリ。
 */
public interface AnalyticsDailyModulesRepository extends JpaRepository<AnalyticsDailyModulesEntity, Long> {

    List<AnalyticsDailyModulesEntity> findByDateBetweenOrderByDateAscModuleIdAsc(LocalDate from, LocalDate to);

    List<AnalyticsDailyModulesEntity> findByModuleIdAndDateBetweenOrderByDateAsc(
            Long moduleId, LocalDate from, LocalDate to);

    List<AnalyticsDailyModulesEntity> findByDate(LocalDate date);
}
