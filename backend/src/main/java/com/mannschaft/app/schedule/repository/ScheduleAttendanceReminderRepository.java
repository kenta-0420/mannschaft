package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 未送信かつ実効リマインド時刻を過ぎたリマインダーを、ID キーセットページングで取得する。
     *
     * <p>ABSOLUTE は {@code remind_at <= :now}、RELATIVE は親予定
     * （{@link com.mannschaft.app.schedule.entity.ScheduleEntity}）の {@code start_at} を
     * SQL 側で結合し {@code start_at <= now + remindBeforeMinutes} で due 判定する（アプリ側での
     * 全件フィルタは行わない）。{@code cursorId} 以降を {@code id} 昇順で {@code pageable} 件のみ返す。</p>
     *
     * @param now      現在時刻
     * @param cursorId 直前ページ最終 ID（先頭ページは 0）
     * @param pageable 取得件数上限（{@code Pageable} の sort は無視され常に id 昇順）
     */
    @Query("SELECT r FROM ScheduleAttendanceReminderEntity r, ScheduleEntity s "
            + "WHERE r.scheduleId = s.id AND r.isSent = false AND r.id > :cursorId AND ("
            + "  (r.reminderKind = com.mannschaft.app.schedule.ReminderKind.ABSOLUTE AND r.remindAt <= :now)"
            + "  OR"
            + "  (r.reminderKind = com.mannschaft.app.schedule.ReminderKind.RELATIVE AND r.remindBeforeMinutes IS NOT NULL"
            + "   AND s.startAt <= FUNCTION('TIMESTAMPADD', MINUTE, r.remindBeforeMinutes, :now))"
            + ") ORDER BY r.id ASC")
    List<ScheduleAttendanceReminderEntity> findDuePage(
            @Param("now") LocalDateTime now, @Param("cursorId") Long cursorId, Pageable pageable);

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
