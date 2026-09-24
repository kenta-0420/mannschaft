package com.mannschaft.app.provisioning.dto;

import java.time.Instant;

/**
 * 柱②-2: 招待の下見（承諾前の確認画面用）応答。
 *
 * @param teamId         対象チーム ID（team 招待のみ）
 * @param organizationId 対象組織 ID（organization 招待のみ）
 * @param scopeName      対象の表示名
 * @param inviteEmail    招待先メールアドレス（承諾ボタン活性判定に FE が使う）
 * @param expiresAt      有効期限
 */
public record ProvisioningInvitationPreviewResponse(
        Long teamId,
        Long organizationId,
        String scopeName,
        String inviteEmail,
        Instant expiresAt
) {
}
