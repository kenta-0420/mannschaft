package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleAnnualCopyLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 年間行事コピーログリポジトリ。
 */
public interface ScheduleAnnualCopyLogRepository extends JpaRepository<ScheduleAnnualCopyLogEntity, Long> {

    /**
     * チームの指定年度コピーログを取得する（新しい順）。
     */
    List<ScheduleAnnualCopyLogEntity> findByTeamIdAndTargetAcademicYearOrderByCreatedAtDesc(
            Long teamId, Integer year);

    /**
     * 組織の指定年度コピーログを取得する（新しい順）。
     */
    List<ScheduleAnnualCopyLogEntity> findByOrganizationIdAndTargetAcademicYearOrderByCreatedAtDesc(
            Long orgId, Integer year);

    /**
     * チームの全コピーログを取得する（新しい順）。
     */
    List<ScheduleAnnualCopyLogEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    /**
     * 組織の全コピーログを取得する（新しい順）。
     */
    List<ScheduleAnnualCopyLogEntity> findByOrganizationIdOrderByCreatedAtDesc(Long orgId);
}
