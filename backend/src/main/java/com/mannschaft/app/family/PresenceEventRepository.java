package com.mannschaft.app.family;

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

    /**
     * 指定ユーザーの直前の未帰宅GOING_OUTイベントを取得する。
     */
    Optional<PresenceEventEntity> findFirstByTeamIdAndUserIdAndEventTypeAndReturnedAtIsNullOrderByCreatedAtDesc(
            Long teamId, Long userId, EventType eventType);

    /**
     * 全チームの指定ユーザーの未帰宅GOING_OUTイベントを取得する。
     */
    List<PresenceEventEntity> findByUserIdAndEventTypeAndReturnedAtIsNull(Long userId, EventType eventType);

    /**
     * チームメンバーの最新プレゼンスイベントを取得する。
     */
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

    /**
     * チームのプレゼンス履歴をカーソルページネーションで取得する。
     */
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

    /**
     * 帰宅遅延バッチ用：未帰宅かつ指定遅延レベルのGOING_OUTイベントを検索する。
     */
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

    /**
     * 指定日時より前のイベントを削除する（クリーンアップバッチ用）。
     */
    void deleteByCreatedAtBefore(LocalDateTime threshold);

    /**
     * プレゼンス統計用：期間内のチームイベントを取得する。
     */
    List<PresenceEventEntity> findByTeamIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long teamId, LocalDateTime after);
}
