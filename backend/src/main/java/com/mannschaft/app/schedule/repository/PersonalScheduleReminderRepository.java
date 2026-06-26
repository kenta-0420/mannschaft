package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * flushAutomatically=true で DELETE を即時 DB に送出し、後続の saveAll() との
     * 競合（uq_psr_schedule_minutes 重複エラー）を防ぐ。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    void deleteByScheduleId(Long scheduleId);

    /**
     * 通知対象のリマインダーを取得する（機能55 第二陣で相対/絶対両対応）。
     *
     * <p>未通知かつ個人スケジュール（userId IS NOT NULL）かつ未削除のスケジュールで、次のいずれか:</p>
     * <ul>
     *   <li>RELATIVE: 開始日時までの残り分数が {@code remindBeforeMinutes} 以下</li>
     *   <li>ABSOLUTE: {@code remindAt} が現在時刻以前</li>
     * </ul>
     */
    @Query("SELECT r FROM PersonalScheduleReminderEntity r " +
            "JOIN ScheduleEntity s ON r.scheduleId = s.id " +
            "WHERE r.notified = false " +
            "AND s.userId IS NOT NULL " +
            "AND s.deletedAt IS NULL " +
            "AND ((r.reminderKind = com.mannschaft.app.schedule.ReminderKind.RELATIVE " +
            "      AND r.remindBeforeMinutes IS NOT NULL " +
            "      AND FUNCTION('TIMESTAMPDIFF', MINUTE, CURRENT_TIMESTAMP, s.startAt) <= r.remindBeforeMinutes) " +
            "  OR (r.reminderKind = com.mannschaft.app.schedule.ReminderKind.ABSOLUTE " +
            "      AND r.remindAt IS NOT NULL " +
            "      AND r.remindAt <= CURRENT_TIMESTAMP))")
    List<PersonalScheduleReminderEntity> findDueReminders();

    /**
     * スケジュールIDでリマインダー件数を取得する。
     */
    long countByScheduleId(Long scheduleId);
}
