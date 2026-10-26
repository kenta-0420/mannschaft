package com.mannschaft.app.digest.repository;

import com.mannschaft.app.digest.DigestScopeType;
import com.mannschaft.app.digest.ScheduleType;
import com.mannschaft.app.digest.entity.TimelineDigestConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * タイムラインダイジェスト設定リポジトリ。
 */
public interface TimelineDigestConfigRepository extends JpaRepository<TimelineDigestConfigEntity, Long> {

    /**
     * スコープの有効な設定を取得する。
     */
    Optional<TimelineDigestConfigEntity> findByScopeTypeAndScopeId(DigestScopeType scopeType, Long scopeId);

    /**
     * 自動スケジュールバッチの対象設定を取得する。
     */
    @Query("SELECT c FROM TimelineDigestConfigEntity c " +
           "WHERE c.isEnabled = true " +
           "AND c.scheduleType <> :manualType " +
           "AND (c.lastExecutedAt IS NULL OR c.lastExecutedAt < :todayStart) " +
           "AND c.scheduleTime <= :currentTime")
    List<TimelineDigestConfigEntity> findScheduledConfigs(
            @Param("manualType") ScheduleType manualType,
            @Param("todayStart") LocalDateTime todayStart,
            @Param("currentTime") LocalTime currentTime);
}
