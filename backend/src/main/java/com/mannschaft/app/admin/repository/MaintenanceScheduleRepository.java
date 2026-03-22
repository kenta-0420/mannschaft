package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.MaintenanceStatus;
import com.mannschaft.app.admin.entity.MaintenanceScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * メンテナンススケジュールリポジトリ。
 */
public interface MaintenanceScheduleRepository extends JpaRepository<MaintenanceScheduleEntity, Long> {

    /**
     * ステータス別にメンテナンススケジュール一覧を取得する。
     */
    List<MaintenanceScheduleEntity> findByStatusOrderByStartsAtAsc(MaintenanceStatus status);

    /**
     * ステータスが指定値のいずれかのスケジュールを取得する。
     */
    List<MaintenanceScheduleEntity> findByStatusInOrderByStartsAtDesc(List<MaintenanceStatus> statuses);
}
