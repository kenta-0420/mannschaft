package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.UserScheduleGoogleEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * スケジュール・Googleイベントマッピングリポジトリ。
 */
public interface UserScheduleGoogleEventRepository extends JpaRepository<UserScheduleGoogleEventEntity, Long> {

    /**
     * ユーザーIDとスケジュールIDでマッピングを取得する。
     */
    Optional<UserScheduleGoogleEventEntity> findByUserIdAndScheduleId(Long userId, Long scheduleId);

    /**
     * ユーザーIDでマッピング一覧を取得する。
     */
    List<UserScheduleGoogleEventEntity> findByUserId(Long userId);

    /**
     * スケジュールIDでマッピング一覧を取得する。
     */
    List<UserScheduleGoogleEventEntity> findByScheduleId(Long scheduleId);

    /**
     * ユーザーIDでマッピングを削除する。
     */
    void deleteByUserId(Long userId);

    /**
     * ユーザーIDとスケジュールIDでマッピングを削除する。
     */
    void deleteByUserIdAndScheduleId(Long userId, Long scheduleId);

    /**
     * ユーザーIDでマッピングを全件削除する。
     */
    @Modifying
    @Query("DELETE FROM UserScheduleGoogleEventEntity e WHERE e.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    /**
     * スコープ指定で未同期スケジュール件数を取得する。
     * user_schedule_google_eventsに存在しないスケジュールを未同期とみなす。
     */
    @Query("SELECT COUNT(s) FROM ScheduleEntity s " +
            "WHERE s.deletedAt IS NULL " +
            "AND ((:scopeType = 'TEAM' AND s.teamId = :scopeId) " +
            "  OR (:scopeType = 'ORGANIZATION' AND s.organizationId = :scopeId)) " +
            "AND NOT EXISTS (SELECT 1 FROM UserScheduleGoogleEventEntity e " +
            "  WHERE e.scheduleId = s.id AND e.userId = :userId)")
    int countUnsyncedSchedules(@Param("userId") Long userId,
                               @Param("scopeType") String scopeType,
                               @Param("scopeId") Long scopeId);

    /**
     * 個人スケジュールの未同期件数を取得する。
     */
    @Query("SELECT COUNT(s) FROM ScheduleEntity s " +
            "WHERE s.userId = :userId AND s.teamId IS NULL AND s.organizationId IS NULL " +
            "AND s.deletedAt IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM UserScheduleGoogleEventEntity e " +
            "  WHERE e.scheduleId = s.id AND e.userId = :userId)")
    int countUnsyncedPersonalSchedules(@Param("userId") Long userId);

    /**
     * 全スコープの未同期スケジュール件数を取得する。
     */
    @Query("SELECT COUNT(s) FROM ScheduleEntity s " +
            "WHERE s.deletedAt IS NULL " +
            "AND (s.userId = :userId OR s.teamId IN " +
            "  (SELECT cs.scopeId FROM UserCalendarSyncSettingEntity cs " +
            "   WHERE cs.userId = :userId AND cs.scopeType = 'TEAM' AND cs.isEnabled = true) " +
            "OR s.organizationId IN " +
            "  (SELECT cs.scopeId FROM UserCalendarSyncSettingEntity cs " +
            "   WHERE cs.userId = :userId AND cs.scopeType = 'ORGANIZATION' AND cs.isEnabled = true)) " +
            "AND NOT EXISTS (SELECT 1 FROM UserScheduleGoogleEventEntity e " +
            "  WHERE e.scheduleId = s.id AND e.userId = :userId)")
    int countAllUnsyncedSchedules(@Param("userId") Long userId);
}
