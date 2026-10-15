package com.mannschaft.app.membership.dto;

import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;

import java.time.LocalDateTime;

/**
 * メンバーシップレスポンス DTO。
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §6.2</p>
 */
public record MembershipDto(
        Long id,
        Long userId,
        ScopeType scopeType,
        Long scopeId,
        RoleKind roleKind,
        LocalDateTime joinedAt,
        LocalDateTime leftAt,
        LeaveReason leaveReason,
        Long invitedBy,
        boolean isRejoin
) {

    /**
     * エンティティから DTO を構築する。
     *
     * @param entity 元エンティティ
     * @param isRejoin 再加入かどうか（呼び出し元で履歴照会して算出）
     */
    public static MembershipDto from(MembershipEntity entity, boolean isRejoin) {
        return new MembershipDto(
                entity.getId(),
                entity.getUserId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getRoleKind(),
                entity.getJoinedAt(),
                entity.getLeftAt(),
                entity.getLeaveReason(),
                entity.getInvitedBy(),
                isRejoin
        );
    }

    public static MembershipDto from(MembershipEntity entity) {
        return from(entity, false);
    }
}
