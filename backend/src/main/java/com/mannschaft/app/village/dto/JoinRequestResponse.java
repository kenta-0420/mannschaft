package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村参加申請レスポンス（F17.1 Phase 1 B6）。
 *
 * <p>{@code reviewedBy} は審査を行った村長/長老のメンバーシップID（{@link UUID}）を返す。
 * 設計書 §3.7 では {@code reviewer_membership_id} を保持しているため村ドメイン内で完結する。</p>
 */
public record JoinRequestResponse(
        UUID id,
        UUID villageId,
        VillageSubjectType subjectType,
        Long subjectId,
        String message,
        VillageRequestStatus status,
        UUID reviewedBy,
        LocalDateTime reviewedAt,
        String reviewComment,
        LocalDateTime createdAt
) {

    public static JoinRequestResponse from(VillageJoinRequestEntity e) {
        return new JoinRequestResponse(
                e.getId(),
                e.getVillageId(),
                e.getSubjectType(),
                e.getSubjectId(),
                e.getMessage(),
                e.getStatus(),
                e.getReviewerMembershipId(),
                e.getReviewedAt(),
                e.getReviewComment(),
                e.getCreatedAt()
        );
    }
}
