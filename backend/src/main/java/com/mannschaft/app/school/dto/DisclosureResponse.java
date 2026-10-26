package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity;

import java.time.LocalDateTime;

/** 出席要件評価の開示判断レスポンスDTO（F03.13 Phase 15）。 */
public record DisclosureResponse(
        Long id,
        Long evaluationId,
        Long studentUserId,
        String decision,
        String mode,
        String recipients,
        String message,
        Long decidedBy,
        LocalDateTime decidedAt
) {
    /**
     * エンティティからDTOを生成するファクトリーメソッド。
     *
     * @param e 開示判断記録エンティティ
     * @return DisclosureResponse
     */
    public static DisclosureResponse from(AttendanceDisclosureRecordEntity e) {
        return new DisclosureResponse(
                e.getId(),
                e.getEvaluationId(),
                e.getStudentUserId(),
                e.getDecision() != null ? e.getDecision().name() : null,
                e.getMode() != null ? e.getMode().name() : null,
                e.getRecipients() != null ? e.getRecipients().name() : null,
                e.getMessage(),
                e.getDecidedBy(),
                e.getDecidedAt()
        );
    }
}
