package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * シフト枠リポジトリ。
 */
public interface ShiftSlotRepository extends JpaRepository<ShiftSlotEntity, Long> {

    /**
     * スケジュールの全シフト枠を日付・開始時刻順で取得する。
     */
    List<ShiftSlotEntity> findByScheduleIdOrderBySlotDateAscStartTimeAsc(Long scheduleId);

    /**
     * スケジュールの特定日のシフト枠を取得する。
     */
    List<ShiftSlotEntity> findByScheduleIdAndSlotDateOrderByStartTimeAsc(Long scheduleId, LocalDate slotDate);

    /**
     * スケジュールIDで全シフト枠を削除する。
     */
    void deleteByScheduleId(Long scheduleId);

    /**
     * スケジュールのシフト枠数を取得する。
     */
    long countByScheduleId(Long scheduleId);
}
