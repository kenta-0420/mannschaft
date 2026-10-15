package com.mannschaft.app.membership.dto;

import java.time.LocalDateTime;

/**
 * 統一メンバー DTO（{@code GET /teams/{id}/members} 等の互換維持用）。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。MemberQueryDispatcher が user_roles
 * 由来（権限ロール）と memberships 由来（MEMBER/SUPPORTER）を統一して返す際に使用する。</p>
 *
 * <p>{@code roleName} は OQ-2 確定の優先度で 1 値返す:
 * SYSTEM_ADMIN &gt; ADMIN &gt; DEPUTY_ADMIN &gt; MEMBER &gt; SUPPORTER &gt; GUEST</p>
 *
 * <p>既存の {@link com.mannschaft.app.role.dto.MemberResponse} と DTO 形は互換。
 * 将来的に DTO 拡張（permissionRole + membershipRoleKind の分離 field）が入る場合は
 * 本 record を拡張して実装する。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §6.1 / §13.6.4</p>
 */
public record MemberDto(
        Long userId,
        String displayName,
        String avatarUrl,
        String roleName,
        LocalDateTime joinedAt
) {
}
