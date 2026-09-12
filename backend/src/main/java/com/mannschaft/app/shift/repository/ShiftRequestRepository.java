package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * シフト希望リポジトリ。
 */
public interface ShiftRequestRepository extends JpaRepository<ShiftRequestEntity, Long>,
        JpaSpecificationExecutor<ShiftRequestEntity> {

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
     * スケジュール・ユーザー・<b>枠</b>で希望を検索する（枠単位の重複チェック用。設計 §11.5.1）。
     *
     * <p>同一日に枠が複数あるとき、希望は<b>枠ごとに 1 件</b>成立する。</p>
     */
    Optional<ShiftRequestEntity> findByScheduleIdAndUserIdAndSlotId(Long scheduleId, Long userId, Long slotId);

    /**
     * スケジュール・ユーザー・日付で<b>日単位希望</b>（{@code slotId IS NULL}）を検索する
     *（重複チェック用。設計 §11.5.1「{@code slotId} が NULL の日単位希望は従来どおり日で判定」）。
     */
    Optional<ShiftRequestEntity> findByScheduleIdAndUserIdAndSlotIdIsNullAndSlotDate(
            Long scheduleId, Long userId, LocalDate slotDate);

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

    /**
     * 指定スケジュール ID の希望を物理削除する（ARCHIVED 30 日後クリーンアップ用）。
     */
    @Modifying
    @Query("DELETE FROM ShiftRequestEntity r WHERE r.scheduleId IN :scheduleIds")
    int deleteByScheduleIds(@Param("scheduleIds") List<Long> scheduleIds);
}
