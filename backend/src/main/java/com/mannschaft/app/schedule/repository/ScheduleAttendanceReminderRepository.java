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
     * 未送信かつリマインド日時を過ぎたリマインダーを取得する（ABSOLUTE 専用・後方互換）。
     */
    List<ScheduleAttendanceReminderEntity> findByIsSentFalseAndRemindAtBeforeOrderByRemindAtAsc(LocalDateTime now);

    /**
     * 未送信のリマインダーを全件取得する（機能55 第二陣）。
     *
     * <p>RELATIVE 指定は {@code remind_at} が NULL のため、実効時刻の due 判定は
     * サービス層で親予定の開始時刻を解決して行う。ABSOLUTE/RELATIVE を区別せず未送信を返す。</p>
     */
    List<ScheduleAttendanceReminderEntity> findByIsSentFalse();

    /**
     * スケジュールIDでリマインダー数を取得する。
     */
    long countByScheduleId(Long scheduleId);

    /**
     * スケジュールIDに紐づくリマインダーをすべて削除する（機能55 BE対応）。
     *
     * <p>リマインダー更新（差し替え）時に既存を全削除してから新規登録するために使用する。</p>
     *
     * @param scheduleId 親予定 schedules.id
     */
    void deleteByScheduleId(Long scheduleId);
}
