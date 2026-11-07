package com.mannschaft.app.role.dto;

import java.util.UUID;

/** 承諾の確保（claim）が成功した証。以降のロール付与・確定・補償はこの claim の主キーで指す。 */
public record InviteClaimTicket(
        UUID claimId,
        Long tokenId,
        String scopeType,
        Long scopeId,
        Long roleId,
        Long invitedBy) {
}
