package com.mannschaft.app.provisioning.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 柱②-2: SYSTEM_ADMIN によるチームプロビジョニング作成リクエスト。
 *
 * @param organizationId 所属組織 ID
 * @param name           チーム名
 * @param inviteEmail    管理予定者への招待先メールアドレス（ADMIN 招待）
 */
public record ProvisioningTeamCreateRequest(
        @NotNull Long organizationId,
        @NotBlank String name,
        @NotBlank @Email String inviteEmail
) {
}
