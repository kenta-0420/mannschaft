package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.entity.AnalyticsAlertHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * アラート発火履歴リポジトリ。
 */
public interface AnalyticsAlertHistoryRepository extends JpaRepository<AnalyticsAlertHistoryEntity, Long> {

    Page<AnalyticsAlertHistoryEntity> findByTriggeredAtBetweenOrderByTriggeredAtDesc(
            LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<AnalyticsAlertHistoryEntity> findByRuleIdAndTriggeredAtBetweenOrderByTriggeredAtDesc(
            Long ruleId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    @Query("SELECT a FROM AnalyticsAlertHistoryEntity a WHERE a.ruleId = :ruleId ORDER BY a.triggeredAt DESC")
    List<AnalyticsAlertHistoryEntity> findRecentByRuleId(@Param("ruleId") Long ruleId, Pageable pageable);

    Optional<AnalyticsAlertHistoryEntity> findTopByRuleIdAndNotifiedTrueOrderByTriggeredAtDesc(Long ruleId);
}
