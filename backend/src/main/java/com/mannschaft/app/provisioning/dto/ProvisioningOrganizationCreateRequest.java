package com.mannschaft.app.provisioning.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 柱②-2: SYSTEM_ADMIN による組織プロビジョニング作成リクエスト。
 *
 * <p>本 PR では試練（受け入れテスト）のみを設置する。挙動は
 * {@link com.mannschaft.app.provisioning.service.ProvisioningService} 側で後続 PR（出陣）が実装する。</p>
 *
 * @param name                     組織名
 * @param inviteEmail              管理予定者への招待先メールアドレス（ADMIN 招待）
 * @param confirmDuplicate         柱③-A: 同名候補の存在を確認済みとして作成を続行するか（省略時 false）
 * @param duplicateNameFingerprint 柱③-A: {@code confirmDuplicate=true} 時に返送する fingerprint
 */
public record ProvisioningOrganizationCreateRequest(
        @NotBlank String name,
        @NotBlank @Email String inviteEmail,
        boolean confirmDuplicate,
        String duplicateNameFingerprint
) {
    /** 柱③-A フィールド省略時（既存呼び出し互換）は confirmDuplicate=false・fingerprint なしとする。 */
    public ProvisioningOrganizationCreateRequest(String name, String inviteEmail) {
        this(name, inviteEmail, false, null);
    }
}
