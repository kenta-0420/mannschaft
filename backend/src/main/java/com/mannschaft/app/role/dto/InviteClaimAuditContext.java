package com.mannschaft.app.role.dto;

/** claim の確定（GRANTED）時に招待監査イベントを組み立てるための、claim と招待行の突き合わせ結果。 */
public record InviteClaimAuditContext(Long userId, Long teamId, Long organizationId, Long invitedBy) {
}
