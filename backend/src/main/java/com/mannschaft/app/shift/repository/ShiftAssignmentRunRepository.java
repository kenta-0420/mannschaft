package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.ShiftAssignmentRunStatus;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * シフト自動割当実行ログリポジトリ。
 */
public interface ShiftAssignmentRunRepository extends JpaRepository<ShiftAssignmentRunEntity, Long> {

    /**
     * スケジュールIDで実行ログを開始日時降順で取得する。
     */
    List<ShiftAssignmentRunEntity> findAllByScheduleIdOrderByStartedAtDesc(Long scheduleId);

    /**
     * スケジュールIDとステータスで最新の実行ログを1件取得する。
     */
    Optional<ShiftAssignmentRunEntity> findTopByScheduleIdAndStatusOrderByStartedAtDesc(
            Long scheduleId, ShiftAssignmentRunStatus status);
}
