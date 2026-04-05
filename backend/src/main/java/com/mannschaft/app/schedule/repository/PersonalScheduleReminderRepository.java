package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 個人スケジュールリマインダーリポジトリ。
 */
public interface PersonalScheduleReminderRepository extends JpaRepository<PersonalScheduleReminderEntity, Long> {

    /**
     * スケジュールIDでリマインダーを取得する（リマインド時間昇順）。
     */
    List<PersonalScheduleReminderEntity> findByScheduleIdOrderByRemindBeforeMinutesAsc(Long scheduleId);

    /**
     * スケジュールIDでリマインダーを削除する。
     */
    void deleteByScheduleId(Long scheduleId);

    /**
     * 通知対象のリマインダーを取得する。
     * 未通知かつ個人スケジュール（userId IS NOT NULL）かつ未削除のスケジュールで、
     * 開始日時までの残り分数がリマインド設定以下のものを返す。
     */
    @Query("SELECT r FROM PersonalScheduleReminderEntity r " +
            "JOIN ScheduleEntity s ON r.scheduleId = s.id " +
            "WHERE r.notified = false " +
            "AND s.userId IS NOT NULL " +
            "AND s.deletedAt IS NULL " +
            "AND FUNCTION('TIMESTAMPDIFF', MINUTE, CURRENT_TIMESTAMP, s.startAt) <= r.remindBeforeMinutes")
    List<PersonalScheduleReminderEntity> findDueReminders();

    /**
     * スケジュールIDでリマインダー件数を取得する。
     */
    long countByScheduleId(Long scheduleId);
}
