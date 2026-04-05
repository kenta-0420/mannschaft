package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.EventType;
import com.mannschaft.app.family.entity.PresenceEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * プレゼンスイベントリポジトリ。
 */
public interface PresenceEventRepository extends JpaRepository<PresenceEventEntity, Long> {

    Optional<PresenceEventEntity> findFirstByTeamIdAndUserIdAndEventTypeAndReturnedAtIsNullOrderByCreatedAtDesc(
            Long teamId, Long userId, EventType eventType);

    List<PresenceEventEntity> findByUserIdAndEventTypeAndReturnedAtIsNull(Long userId, EventType eventType);

    @Query("""
            SELECT pe FROM PresenceEventEntity pe
            WHERE pe.teamId = :teamId
              AND pe.createdAt = (
                SELECT MAX(pe2.createdAt) FROM PresenceEventEntity pe2
                WHERE pe2.teamId = pe.teamId AND pe2.userId = pe.userId
              )
            ORDER BY pe.createdAt DESC
            """)
    List<PresenceEventEntity> findLatestByTeamId(@Param("teamId") Long teamId);

    @Query("""
            SELECT pe FROM PresenceEventEntity pe
            WHERE pe.teamId = :teamId
              AND (:userId IS NULL OR pe.userId = :userId)
              AND (:cursor IS NULL OR pe.id < :cursor)
            ORDER BY pe.id DESC
            """)
    List<PresenceEventEntity> findHistory(
            @Param("teamId") Long teamId,
            @Param("userId") Long userId,
            @Param("cursor") Long cursor,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT pe FROM PresenceEventEntity pe
            WHERE pe.eventType = 'GOING_OUT'
              AND pe.returnedAt IS NULL
              AND pe.expectedReturnAt IS NOT NULL
              AND pe.overdueLevel = :level
              AND pe.expectedReturnAt < :threshold
            """)
    List<PresenceEventEntity> findOverdueEvents(
            @Param("level") int level,
            @Param("threshold") LocalDateTime threshold);

    void deleteByCreatedAtBefore(LocalDateTime threshold);

    List<PresenceEventEntity> findByTeamIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long teamId, LocalDateTime after);
}
