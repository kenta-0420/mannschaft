package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 出席要件評価レスポンスDTO（F03.13 Phase 12）。 */
public record EvaluationResponse(
        Long id,
        Long requirementRuleId,
        Long studentUserId,
        Long summaryId,
        AttendanceRequirementEvaluationEntity.EvaluationStatus status,
        BigDecimal currentAttendanceRate,
        int remainingAllowedAbsences,
        LocalDateTime evaluatedAt,
        LocalDateTime resolvedAt,
        String resolutionNote,
        Long resolverUserId
) {
    /**
     * エンティティからDTOを生成するファクトリーメソッド。
     *
     * @param e 出席要件評価エンティティ
     * @return EvaluationResponse
     */
    public static EvaluationResponse from(AttendanceRequirementEvaluationEntity e) {
        return new EvaluationResponse(
                e.getId(), e.getRequirementRuleId(), e.getStudentUserId(),
                e.getSummaryId(), e.getStatus(), e.getCurrentAttendanceRate(),
                e.getRemainingAllowedAbsences(), e.getEvaluatedAt(),
                e.getResolvedAt(), e.getResolutionNote(), e.getResolverUserId());
    }
}
