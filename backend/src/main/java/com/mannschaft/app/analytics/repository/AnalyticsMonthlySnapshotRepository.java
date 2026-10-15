package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.entity.AnalyticsMonthlySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 月次スナップショットリポジトリ。
 */
public interface AnalyticsMonthlySnapshotRepository extends JpaRepository<AnalyticsMonthlySnapshotEntity, Long> {

    List<AnalyticsMonthlySnapshotEntity> findByMonthBetweenOrderByMonthAsc(LocalDate from, LocalDate to);

    Optional<AnalyticsMonthlySnapshotEntity> findByMonth(LocalDate month);

    @Query("SELECT MAX(a.month) FROM AnalyticsMonthlySnapshotEntity a")
    Optional<LocalDate> findLatestMonth();
}
