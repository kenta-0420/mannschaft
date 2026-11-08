package com.mannschaft.app.common.architecture.plaintexttoken;

import com.mannschaft.app.common.token.PlaintextSecretRequired;
import com.mannschaft.app.common.token.PlaintextTokenDebt;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * fixture（番人1 陽性＝免除の誤用がハッシュ列・語彙外の列に付いた形）: 注釈の cmp・reason の検証は、
 * 付いた列の種類に関係なく効かなければならない。
 *
 * <ul>
 *   <li>{@code tokenHash}: ハッシュ列に他の戦役の cmp</li>
 *   <li>{@code resetTokenHash}: ハッシュ列に空の reason</li>
 *   <li>{@code note}: 語彙に一致しない列に他の戦役の cmp</li>
 * </ul>
 */
@Entity
@Table(name = "plaintext_guard_fixture_misdeclared_hash")
public class MisdeclaredHashExemptionFixtureEntity {

    @Id
    private Long id;

    @PlaintextTokenDebt(cmp = "OTHER-CMP")
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @PlaintextSecretRequired(reason = "")
    @Column(name = "reset_token_hash", length = 64)
    private String resetTokenHash;

    @PlaintextTokenDebt(cmp = "OTHER-CMP")
    private String note;

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getResetTokenHash() {
        return resetTokenHash;
    }

    public String getNote() {
        return note;
    }
}
