package com.mannschaft.app.common.architecture.plaintexttoken;

import com.mannschaft.app.common.token.PlaintextSecretRequired;
import com.mannschaft.app.common.token.PlaintextTokenDebt;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * fixture（番人1 陽性＝免除の誤用）: 免除注釈が付いているが、宣言として無効なもの。
 *
 * <ul>
 *   <li>{@code webhookSecret}: {@code @PlaintextSecretRequired(reason = "")}（理由が空）</li>
 *   <li>{@code apiSecret}: {@code @PlaintextSecretRequired(reason = "   ")}（理由が空白だけ）</li>
 *   <li>{@code previewToken}: {@code @PlaintextTokenDebt(cmp = 他の戦役)}（債務注釈の流用・AC-27）</li>
 * </ul>
 */
@Entity
@Table(name = "plaintext_guard_fixture_misdeclared")
public class MisdeclaredExemptionFixtureEntity {

    @Id
    private Long id;

    @PlaintextSecretRequired(reason = "")
    private String webhookSecret;

    @PlaintextSecretRequired(reason = "   ")
    private String apiSecret;

    @PlaintextTokenDebt(cmp = "CMP-999999-0000")
    private String previewToken;

    public Long getId() {
        return id;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public String getPreviewToken() {
        return previewToken;
    }
}
