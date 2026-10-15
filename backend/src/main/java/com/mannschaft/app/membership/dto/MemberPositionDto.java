package com.mannschaft.app.membership.dto;

import com.mannschaft.app.membership.entity.MemberPositionEntity;

import java.time.LocalDateTime;

/**
 * 役職割当レスポンス DTO。
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §6.2</p>
 */
public record MemberPositionDto(
        Long id,
        Long membershipId,
        Long positionId,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Long assignedBy
) {

    public static MemberPositionDto from(MemberPositionEntity entity) {
        return new MemberPositionDto(
                entity.getId(),
                entity.getMembershipId(),
                entity.getPositionId(),
                entity.getStartedAt(),
                entity.getEndedAt(),
                entity.getAssignedBy()
        );
    }
}
