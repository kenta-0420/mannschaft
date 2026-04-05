package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.entity.AnalyticsDailyUsersEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 日次ユーザー統計リポジトリ。
 */
public interface AnalyticsDailyUsersRepository extends JpaRepository<AnalyticsDailyUsersEntity, Long> {

    List<AnalyticsDailyUsersEntity> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);

    Optional<AnalyticsDailyUsersEntity> findByDate(LocalDate date);

    @Query("SELECT MAX(a.date) FROM AnalyticsDailyUsersEntity a")
    Optional<LocalDate> findLatestDate();

    void deleteByDate(LocalDate date);
}
