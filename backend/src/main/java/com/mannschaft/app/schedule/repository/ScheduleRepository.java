package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * スケジュールリポジトリ。
 */
public interface ScheduleRepository extends JpaRepository<ScheduleEntity, Long> {

    /**
     * チームスコープのスケジュールを期間指定で取得する。
     */
    List<ScheduleEntity> findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
            Long teamId, LocalDateTime from, LocalDateTime to);

    /**
     * 組織スコープのスケジュールを期間指定で取得する。
     */
    List<ScheduleEntity> findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(
            Long orgId, LocalDateTime from, LocalDateTime to);

    /**
     * 個人スコープのスケジュールを期間指定で取得する。
     */
    List<ScheduleEntity> findByUserIdAndStartAtBetweenOrderByStartAtAsc(
            Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * IDとチームIDでスケジュールを取得する。
     */
    Optional<ScheduleEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * IDと組織IDでスケジュールを取得する。
     */
    Optional<ScheduleEntity> findByIdAndOrganizationId(Long id, Long orgId);

    /**
     * 親スケジュールに紐付く子スケジュールを取得する。
     */
    List<ScheduleEntity> findByParentScheduleIdOrderByStartAtAsc(Long parentId);

    /**
     * 親スケジュールに紐付く子スケジュール数を取得する。
     */
    long countByParentScheduleId(Long parentId);

    /**
     * 完了可能なスケジュール（終了日時を過ぎた予定ステータス）を取得する。
     */
    @Query("SELECT s FROM ScheduleEntity s WHERE s.status = 'SCHEDULED' AND s.endAt < :now AND s.endAt IS NOT NULL")
    List<ScheduleEntity> findCompletableSchedules(@Param("now") LocalDateTime now);

    /**
     * チームスコープの今後のスケジュール数を取得する。
     */
    long countByTeamIdAndStartAtAfter(Long teamId, LocalDateTime after);

    /**
     * 組織スコープの今後のスケジュール数を取得する。
     */
    long countByOrganizationIdAndStartAtAfter(Long orgId, LocalDateTime after);

    /**
     * 未同期のスコープ指定スケジュールを取得する（Google Calendar同期用）。
     */
    @Query(value = "SELECT s.* FROM schedules s " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN s.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN s.organization_id = :scopeId END " +
            "AND s.deleted_at IS NULL " +
            "AND s.id NOT IN (SELECT ge.schedule_id FROM user_schedule_google_events ge WHERE ge.user_id = :userId)",
            nativeQuery = true)
    List<ScheduleEntity> findUnsyncedByUserAndScope(
            @Param("userId") Long userId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 未同期の個人スケジュールを取得する（Google Calendar同期用）。
     */
    @Query(value = "SELECT s.* FROM schedules s " +
            "WHERE s.user_id = :userId AND s.team_id IS NULL AND s.organization_id IS NULL " +
            "AND s.deleted_at IS NULL " +
            "AND s.id NOT IN (SELECT ge.schedule_id FROM user_schedule_google_events ge WHERE ge.user_id = :userId)",
            nativeQuery = true)
    List<ScheduleEntity> findUnsyncedPersonalSchedules(@Param("userId") Long userId);

    @Query("SELECT s FROM ScheduleEntity s WHERE s.title LIKE %:keyword% OR s.description LIKE %:keyword% OR s.location LIKE %:keyword%")
    List<ScheduleEntity> searchByKeyword(@Param("keyword") String keyword, org.springframework.data.domain.Pageable pageable);

    /**
     * チームの最頻利用施設（venue_id）を取得する（広告セグメント用）。
     */
    @Query(value = "SELECT s.venue_id, COUNT(*) AS cnt FROM schedules s " +
            "WHERE s.team_id = :teamId AND s.venue_id IS NOT NULL AND s.deleted_at IS NULL " +
            "GROUP BY s.venue_id ORDER BY cnt DESC LIMIT 1",
            nativeQuery = true)
    List<Object[]> findTopVenueByTeamId(@Param("teamId") Long teamId);

    /**
     * F03.15 Phase 4: external_ref に紐付くスケジュールを取得する（idempotency 用）。
     */
    Optional<ScheduleEntity> findByExternalRef(String externalRef);

    /**
     * F03.15 Phase 4: 指定 external_ref のスケジュールを論理削除する。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE ScheduleEntity s SET s.deletedAt = CURRENT_TIMESTAMP WHERE s.externalRef = :externalRef AND s.deletedAt IS NULL")
    int softDeleteByExternalRef(@Param("externalRef") String externalRef);

    /**
     * F03.15 Phase 4: external_ref のプレフィックス検索（取消フロー用）。
     */
    @Query("SELECT s FROM ScheduleEntity s WHERE s.externalRef LIKE :prefix AND s.deletedAt IS NULL")
    List<ScheduleEntity> findByExternalRefPrefix(@Param("prefix") String prefix);
}
