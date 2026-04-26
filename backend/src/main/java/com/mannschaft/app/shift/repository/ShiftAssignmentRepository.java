package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
