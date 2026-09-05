package com.mannschaft.app.joinrequest.dto;

import com.mannschaft.app.joinrequest.entity.JoinRequestEntity;
import com.mannschaft.app.joinrequest.entity.JoinRequestStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 参加申請レスポンス（柱③-A・CMP-260901-1538）。
 */
public record JoinRequestResponse(
        UUID id,
        String scopeType,
        Long scopeId,
        Long requesterUserId,
        String message,
        JoinRequestStatus status,
        Long reviewerUserId,
        LocalDateTime reviewedAt,
        String reviewComment,
        LocalDateTime createdAt
) {

    public static JoinRequestResponse from(JoinRequestEntity e) {
        return new JoinRequestResponse(
                e.getId(),
                e.scopeType(),
                e.scopeId(),
                e.getRequesterUserId(),
                e.getMessage(),
                e.getStatus(),
                e.getReviewerUserId(),
                e.getReviewedAt(),
                e.getReviewComment(),
                e.getCreatedAt()
        );
    }
}
