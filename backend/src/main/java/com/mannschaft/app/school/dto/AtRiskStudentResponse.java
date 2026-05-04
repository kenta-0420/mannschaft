package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** リスクありの生徒レスポンスDTO（F03.13 Phase 12）。 */
public record AtRiskStudentResponse(
        Long studentUserId,
        AttendanceRequirementEvaluationEntity.EvaluationStatus status,
        Long requirementRuleId,
        BigDecimal currentAttendanceRate,
        int remainingAllowedAbsences,
        LocalDateTime evaluatedAt
) {
    /**
     * エンティティからDTOを生成するファクトリーメソッド。
     *
     * @param e 出席要件評価エンティティ
     * @return AtRiskStudentResponse
     */
    public static AtRiskStudentResponse from(AttendanceRequirementEvaluationEntity e) {
        return new AtRiskStudentResponse(
                e.getStudentUserId(), e.getStatus(), e.getRequirementRuleId(),
                e.getCurrentAttendanceRate(), e.getRemainingAllowedAbsences(), e.getEvaluatedAt());
    }
}
