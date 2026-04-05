package com.mannschaft.app.queue.repository;

import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.entity.QueueDailyStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 順番待ち日次統計リポジトリ。
 */
public interface QueueDailyStatsRepository extends JpaRepository<QueueDailyStatsEntity, Long> {

    /**
     * カウンターIDと日付で統計を取得する。
     */
    Optional<QueueDailyStatsEntity> findByCounterIdAndStatDate(Long counterId, LocalDate statDate);

    /**
     * スコープ指定で日付範囲の統計を取得する。
     */
    List<QueueDailyStatsEntity> findByScopeTypeAndScopeIdAndStatDateBetweenOrderByStatDateAsc(
            QueueScopeType scopeType, Long scopeId, LocalDate from, LocalDate to);

    /**
     * カウンターIDで日付範囲の統計を取得する。
     */
    List<QueueDailyStatsEntity> findByCounterIdAndStatDateBetweenOrderByStatDateAsc(
            Long counterId, LocalDate from, LocalDate to);
}
