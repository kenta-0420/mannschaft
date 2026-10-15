package com.mannschaft.app.school.repository;

import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** 出席要件規程リポジトリ。 */
public interface AttendanceRequirementRuleRepository extends JpaRepository<AttendanceRequirementRuleEntity, Long> {

    /** 組織スコープの規程一覧を学年度・有効期間でフィルタして取得する。 */
    @Query("SELECT r FROM AttendanceRequirementRuleEntity r " +
           "WHERE r.organizationId = :orgId AND r.academicYear = :year " +
           "AND (r.effectiveUntil IS NULL OR r.effectiveUntil >= :today)")
    List<AttendanceRequirementRuleEntity> findByOrganizationIdAndAcademicYear(
        @Param("orgId") Long orgId,
        @Param("year") short year,
        @Param("today") LocalDate today);

    /** チームスコープの規程一覧を学年度・有効期間でフィルタして取得する。 */
    @Query("SELECT r FROM AttendanceRequirementRuleEntity r " +
           "WHERE r.teamId = :teamId AND r.academicYear = :year " +
           "AND (r.effectiveUntil IS NULL OR r.effectiveUntil >= :today)")
    List<AttendanceRequirementRuleEntity> findByTeamIdAndAcademicYear(
        @Param("teamId") Long teamId,
        @Param("year") short year,
        @Param("today") LocalDate today);

    /** バッチ用: 全スコープの有効規程を学年度・有効期間でフィルタして一括取得する。 */
    @Query("SELECT r FROM AttendanceRequirementRuleEntity r " +
           "WHERE r.academicYear = :year " +
           "AND (r.effectiveUntil IS NULL OR r.effectiveUntil >= :today)")
    List<AttendanceRequirementRuleEntity> findAllActive(
        @Param("today") LocalDate today,
        @Param("year") short year);
}
