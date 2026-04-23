package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * スケジュールと preference で希望件数を集計する（v2: 5 段階集計用）。
     */
    long countByScheduleIdAndPreference(Long scheduleId, ShiftPreference preference);

    /**
     * スケジュール単位で preference 別の希望件数を 1 クエリで集計する（v2: 5 段階集計用）。
     *
     * <p>戻り値は {@code [preference, count]} の配列リスト。カテゴリ別件数は
     * Service 層で Map に詰め替えて利用する。</p>
     */
    @Query("SELECT r.preference AS preference, COUNT(r) AS cnt "
            + "FROM ShiftRequestEntity r "
            + "WHERE r.scheduleId = :scheduleId "
            + "GROUP BY r.preference")
    List<Object[]> countByPreferenceForSchedule(@Param("scheduleId") Long scheduleId);
}
