package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * シフト希望リポジトリ。
 */
public interface ShiftRequestRepository extends JpaRepository<ShiftRequestEntity, Long> {

    /**
     * スケジュールの全希望を取得する。
     */
    List<ShiftRequestEntity> findByScheduleIdOrderBySlotDateAsc(Long scheduleId);

    /**
     * スケジュールとユーザーで希望を取得する。
     */
    List<ShiftRequestEntity> findByScheduleIdAndUserId(Long scheduleId, Long userId);

    /**
     * スケジュールと日付で希望を取得する。
     */
    List<ShiftRequestEntity> findByScheduleIdAndSlotDate(Long scheduleId, LocalDate slotDate);

    /**
     * スケジュール・ユーザー・日付で希望を検索する（重複チェック用）。
     */
    Optional<ShiftRequestEntity> findByScheduleIdAndUserIdAndSlotDate(Long scheduleId, Long userId, LocalDate slotDate);

    /**
     * スケジュールの希望提出ユーザー数を取得する。
     */
    long countDistinctUserIdByScheduleId(Long scheduleId);

    /**
     * ユーザーの全希望を取得する。
     */
    List<ShiftRequestEntity> findByUserIdOrderBySlotDateDesc(Long userId);
}
