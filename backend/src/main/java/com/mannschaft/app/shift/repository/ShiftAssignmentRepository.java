package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.ShiftAssignmentStatus;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * シフト割当リポジトリ。
 */
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignmentEntity, Long> {

    /**
     * 実行履歴IDに紐づく割当一覧を取得する。
     */
    List<ShiftAssignmentEntity> findAllByRunId(Long runId);

    /**
     * スロットIDに紐づく割当一覧を取得する。
     */
    List<ShiftAssignmentEntity> findAllBySlotId(Long slotId);

    /**
     * スケジュール ID に紐づく全 slot の割当一覧を一括取得する。
     *
     * <p>Phase 11 事後検分 fixup（2026-05-17）: {@code getScheduleSummary()} の
     * N+1 クエリ問題を解消するため追加。slot 数 N に対して N 回 SQL を発行していた
     * 実装を 1 回の JOIN クエリに統合し、後段は Java 側で {@code slotId} でグルーピングする。</p>
     *
     * <p>JPQL: {@code shift_assignments JOIN shift_slots ON slot_id = slots.id WHERE slots.schedule_id = ?}</p>
     */
    @Query("SELECT a FROM ShiftAssignmentEntity a "
            + "WHERE a.slotId IN (SELECT s.id FROM ShiftSlotEntity s WHERE s.scheduleId = :scheduleId)")
    List<ShiftAssignmentEntity> findAllByScheduleId(@Param("scheduleId") Long scheduleId);

    /**
     * ユーザーIDとステータスで割当一覧を取得する（確定シフト取得用）。
     */
    List<ShiftAssignmentEntity> findAllByUserIdAndStatus(Long userId, ShiftAssignmentStatus status);

    /**
     * 司令塔第二弾（ADHD-UX戦役第四陣）: 個人ダッシュボード「今後の予定」統合用に、
     * 指定ユーザーの CONFIRMED シフト割当を指定期間 {@code [fromDate, untilDate)} で取得する。
     *
     * <p>タイトルはスロットが属するシフトスケジュール（{@link com.mannschaft.app.shift.entity.ShiftScheduleEntity}）
     * の {@code title} を使う（{@link ShiftAssignmentEntity} 自体はタイトルを持たない）。
     * {@code shift_assignments} × {@code shift_slots} × {@code shift_schedules} を 1 クエリで JOIN し、
     * 呼び出し側の件数に関わらず固定 1 クエリで完結させる（N+1 回避・AC-B2-5）。</p>
     *
     * <p>返却は {@code Object[]}: {@code [id(Long), scheduleTitle(String), slotDate(LocalDate),
     * startTime(LocalTime), endTime(LocalTime), teamId(Long)]}。並び順は日付→開始時刻の昇順。</p>
     *
     * @param userId    対象ユーザーID
     * @param fromDate  取得期間の開始日（含む）
     * @param untilDate 取得期間の終了日（含まない・排他的上限）
     */
    @Query("SELECT a.id, sc.title, s.slotDate, s.startTime, s.endTime, sc.teamId "
            + "FROM ShiftAssignmentEntity a, com.mannschaft.app.shift.entity.ShiftSlotEntity s, "
            + "com.mannschaft.app.shift.entity.ShiftScheduleEntity sc "
            + "WHERE a.slotId = s.id AND s.scheduleId = sc.id "
            + "AND a.userId = :userId AND a.status = 'CONFIRMED' "
            + "AND s.slotDate >= :fromDate AND s.slotDate < :untilDate "
            + "ORDER BY s.slotDate ASC, s.startTime ASC")
    List<Object[]> findUpcomingByUserIdBetween(
            @Param("userId") Long userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("untilDate") LocalDate untilDate);
}
