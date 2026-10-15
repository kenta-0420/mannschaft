package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageCreationRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村作成申請レスポンス（F17.1 Phase 1 B5）。
 */
public record VillageCreationRequestResponse(
        UUID id,
        Long requesterUserId,
        String name,
        String slug,
        String category,
        String purpose,
        VillageRequestStatus status,
        Long reviewedBy,
        LocalDateTime reviewedAt,
        String reviewComment,
        UUID createdVillageId,
        LocalDateTime createdAt
) {

    public static VillageCreationRequestResponse from(VillageCreationRequestEntity e) {
        return new VillageCreationRequestResponse(
                e.getId(),
                e.getRequesterUserId(),
                e.getProposedName(),
                e.getProposedSlug(),
                e.getProposedCategory(),
                e.getPurpose(),
                e.getStatus(),
                e.getReviewerUserId(),
                e.getReviewedAt(),
                e.getReviewComment(),
                e.getCreatedVillageId(),
                e.getCreatedAt()
        );
    }
}
