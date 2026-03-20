package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出欠リマインダーリポジトリ。
 */
public interface ScheduleAttendanceReminderRepository extends JpaRepository<ScheduleAttendanceReminderEntity, Long> {

    /**
     * スケジュールIDでリマインダー一覧を取得する。
     */
    List<ScheduleAttendanceReminderEntity> findByScheduleIdOrderByRemindAtAsc(Long scheduleId);

    /**
     * 未送信かつリマインド日時を過ぎたリマインダーを取得する。
     */
    List<ScheduleAttendanceReminderEntity> findByIsSentFalseAndRemindAtBeforeOrderByRemindAtAsc(LocalDateTime now);

    /**
     * スケジュールIDでリマインダー数を取得する。
     */
    long countByScheduleId(Long scheduleId);
}
