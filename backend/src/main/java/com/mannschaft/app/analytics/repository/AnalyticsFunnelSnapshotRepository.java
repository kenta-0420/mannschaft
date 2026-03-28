package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.entity.AnalyticsFunnelSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * ファネルスナップショットリポジトリ。
 */
public interface AnalyticsFunnelSnapshotRepository extends JpaRepository<AnalyticsFunnelSnapshotEntity, Long> {

    List<AnalyticsFunnelSnapshotEntity> findByDateOrderByStageAsc(LocalDate date);

    @Query("SELECT MAX(a.date) FROM AnalyticsFunnelSnapshotEntity a")
    Optional<LocalDate> findLatestDate();
}
