package com.mannschaft.app.joinrequest.dto;

import com.mannschaft.app.joinrequest.entity.JoinRequestEntity;
import com.mannschaft.app.joinrequest.entity.JoinRequestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 参加申請レスポンス（柱③-A・CMP-260901-1538）。
 *
 * <p>日時は {@link Instant}（起きた瞬間）で保持する（{@code DateTimeAndZoneGuardTest} 準拠）。</p>
 */
public record JoinRequestResponse(
        UUID id,
        String scopeType,
        Long scopeId,
        Long requesterUserId,
        String message,
        JoinRequestStatus status,
        Long reviewerUserId,
        Instant reviewedAt,
        String reviewComment,
        Instant createdAt
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
