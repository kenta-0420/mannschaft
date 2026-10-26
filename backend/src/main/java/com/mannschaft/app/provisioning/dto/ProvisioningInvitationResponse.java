package com.mannschaft.app.provisioning.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 柱②-2: 販促プロビジョニング招待の一覧/発行応答。平文トークンは含めない
 * （発行直後の応答でのみ別途返す想定。{@code VillageInvitationIssueResponse} と同型）。
 *
 * @param id             招待 ID
 * @param teamId         対象チーム ID（team 招待のみ）
 * @param organizationId 対象組織 ID（organization 招待のみ）
 * @param inviteEmail    招待先メールアドレス
 * @param status         PENDING/ACCEPTED/CANCELLED/EXPIRED
 * @param expiresAt      有効期限
 * @param issuedBy       発行者 user ID
 */
public record ProvisioningInvitationResponse(
        UUID id,
        Long teamId,
        Long organizationId,
        String inviteEmail,
        String status,
        Instant expiresAt,
        Long issuedBy
) {
}
