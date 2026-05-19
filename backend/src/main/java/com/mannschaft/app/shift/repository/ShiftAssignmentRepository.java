package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
