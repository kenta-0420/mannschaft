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
}
