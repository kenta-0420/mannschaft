package com.mannschaft.app.provisioning.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 柱②-2: SYSTEM_ADMIN によるチームプロビジョニング作成リクエスト。
 *
 * <p>チームは組織の子ではない独立したスコープ種別（{@code ScopeType.TEAM}）のため、
 * 組織作成リクエストと異なり所属組織 ID は持たない。</p>
 *
 * @param name        チーム名
 * @param inviteEmail 管理予定者への招待先メールアドレス（ADMIN 招待）
 */
public record ProvisioningTeamCreateRequest(
        @NotBlank String name,
        @NotBlank @Email String inviteEmail
) {
}
