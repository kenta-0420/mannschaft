package com.mannschaft.app.common.architecture.plaintexttoken;

import com.mannschaft.app.common.token.PlaintextSecretRequired;
import com.mannschaft.app.common.token.PlaintextTokenDebt;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * fixture（番人1 陰性＝免除）: 正しく宣言された免除注釈を持つ平文列。
 *
 * <ul>
 *   <li>{@code signingSecret}: {@code @PlaintextSecretRequired}（理由あり・恒久除外）</li>
 *   <li>{@code inviteToken}: {@code @PlaintextTokenDebt(cmp = "CMP-260826-1457")}（移行期間の債務）</li>
 * </ul>
 */
@Entity
@Table(name = "plaintext_guard_fixture_exempted")
public class ExemptedSecretFixtureEntity {

    @Id
    private Long id;

    @PlaintextSecretRequired(reason = "HMAC 署名に平文の鍵が要るため（fixture）")
    private String signingSecret;

    @PlaintextTokenDebt(cmp = "CMP-260826-1457")
    private String inviteToken;

    public Long getId() {
        return id;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public String getInviteToken() {
        return inviteToken;
    }
}
