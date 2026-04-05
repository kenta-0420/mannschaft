package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * スケジュール出欠リポジトリ。
 */
public interface ScheduleAttendanceRepository extends JpaRepository<ScheduleAttendanceEntity, Long> {

    /**
     * スケジュールIDとユーザーIDで出欠を取得する。
     */
    Optional<ScheduleAttendanceEntity> findByScheduleIdAndUserId(Long scheduleId, Long userId);

    /**
     * スケジュールIDで出欠一覧を取得する。
     */
    List<ScheduleAttendanceEntity> findByScheduleIdOrderByUserIdAsc(Long scheduleId);

    /**
     * スケジュールIDとステータスで出欠一覧を取得する。
     */
    List<ScheduleAttendanceEntity> findByScheduleIdAndStatus(Long scheduleId, AttendanceStatus status);

    /**
     * スケジュールIDとステータスで出欠数を取得する。
     */
    long countByScheduleIdAndStatus(Long scheduleId, AttendanceStatus status);

    /**
     * スケジュールIDごとのステータス別出欠数を取得する。
     */
    @Query("SELECT a.status, COUNT(a) FROM ScheduleAttendanceEntity a WHERE a.scheduleId = :scheduleId GROUP BY a.status")
    List<Object[]> countByScheduleIdGroupByStatus(@Param("scheduleId") Long scheduleId);

    /**
     * スケジュールIDで出欠レコードを全削除する。
     */
    void deleteByScheduleId(Long scheduleId);
}
