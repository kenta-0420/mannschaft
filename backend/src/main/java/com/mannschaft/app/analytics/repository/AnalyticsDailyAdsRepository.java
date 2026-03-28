package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.entity.AnalyticsDailyAdsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 日次広告統計リポジトリ。
 */
public interface AnalyticsDailyAdsRepository extends JpaRepository<AnalyticsDailyAdsEntity, Long> {

    List<AnalyticsDailyAdsEntity> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);

    Optional<AnalyticsDailyAdsEntity> findByDate(LocalDate date);
}
