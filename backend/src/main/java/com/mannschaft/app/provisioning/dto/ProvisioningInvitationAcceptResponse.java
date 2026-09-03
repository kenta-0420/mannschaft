package com.mannschaft.app.provisioning.dto;

/**
 * 柱②-2: 招待承諾の応答。承諾者を ADMIN として付与したスコープを返す。
 *
 * @param teamId         対象チーム ID（team 招待のみ）
 * @param organizationId 対象組織 ID（organization 招待のみ）
 * @param scopeName      対象の表示名
 * @param status         承諾後の招待状態（常に ACCEPTED）
 */
public record ProvisioningInvitationAcceptResponse(
        Long teamId,
        Long organizationId,
        String scopeName,
        String status
) {
}
