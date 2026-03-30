package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.MaintenanceScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * メンテナンススケジュールリポジトリ。
 */
public interface MaintenanceScheduleRepository extends JpaRepository<MaintenanceScheduleEntity, Long> {

    /**
     * スコープに紐づく有効スケジュール（未削除・有効）を取得する。
     */
    List<MaintenanceScheduleEntity> findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
            String scopeType, Long scopeId);

    /**
     * 次回実行日が指定日以前かつ有効な未削除スケジュールを取得する（実行バッチ用）。
     */
    List<MaintenanceScheduleEntity> findByNextExecutionDateLessThanEqualAndIsActiveTrueAndDeletedAtIsNull(
            LocalDate date);
}
