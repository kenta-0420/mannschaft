package com.mannschaft.app.provisioning.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 柱②-2: SYSTEM_ADMIN によるチームプロビジョニング作成リクエスト。
 *
 * <p>チームは組織の子ではない独立したスコープ種別（{@code ScopeType.TEAM}）のため、
 * 組織作成リクエストと異なり所属組織 ID は持たない。</p>
 *
 * @param name                     チーム名
 * @param inviteEmail              管理予定者への招待先メールアドレス（ADMIN 招待）
 * @param confirmDuplicate         柱③-A: 同名候補の存在を確認済みとして作成を続行するか（省略時 false）
 * @param duplicateNameFingerprint 柱③-A: {@code confirmDuplicate=true} 時に返送する fingerprint
 */
public record ProvisioningTeamCreateRequest(
        @NotBlank String name,
        @NotBlank @Email String inviteEmail,
        boolean confirmDuplicate,
        String duplicateNameFingerprint
) {
    /** 柱③-A フィールド省略時（既存呼び出し互換）は confirmDuplicate=false・fingerprint なしとする。 */
    public ProvisioningTeamCreateRequest(String name, String inviteEmail) {
        this(name, inviteEmail, false, null);
    }
}
