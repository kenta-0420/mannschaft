package com.mannschaft.app.school.repository;

import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 出席要件評価リポジトリ。 */
public interface AttendanceRequirementEvaluationRepository
        extends JpaRepository<AttendanceRequirementEvaluationEntity, Long> {

    /** 生徒の全規程評価を評価日降順で取得 */
    List<AttendanceRequirementEvaluationEntity> findByStudentUserIdOrderByEvaluatedAtDesc(Long studentUserId);

    /** 生徒×規程の最新評価を取得（upsert判定用） */
    Optional<AttendanceRequirementEvaluationEntity>
    findTopByStudentUserIdAndRequirementRuleIdOrderByEvaluatedAtDesc(
            Long studentUserId, Long requirementRuleId);

    /** チーム内の生徒IDリストに対して指定ステータスの最新評価を取得（at-risk一覧用） */
    @Query("SELECT e FROM AttendanceRequirementEvaluationEntity e " +
           "WHERE e.studentUserId IN :studentUserIds " +
           "AND e.status IN :statuses " +
           "ORDER BY e.evaluatedAt DESC")
    List<AttendanceRequirementEvaluationEntity> findLatestAtRiskByStudentIds(
            @Param("studentUserIds") List<Long> studentUserIds,
            @Param("statuses") List<AttendanceRequirementEvaluationEntity.EvaluationStatus> statuses);

    /**
     * チームに紐づく規程を経由してリスクあり評価を取得する（at-risk一覧用）。
     * teamId の規程に属する評価のうち、指定ステータスのものを評価日降順で返す。
     */
    @Query("SELECT e FROM AttendanceRequirementEvaluationEntity e " +
           "JOIN AttendanceRequirementRuleEntity r ON r.id = e.requirementRuleId " +
           "WHERE r.teamId = :teamId AND e.status IN :statuses " +
           "ORDER BY e.evaluatedAt DESC")
    List<AttendanceRequirementEvaluationEntity> findAtRiskByTeamId(
            @Param("teamId") Long teamId,
            @Param("statuses") List<AttendanceRequirementEvaluationEntity.EvaluationStatus> statuses);
}
