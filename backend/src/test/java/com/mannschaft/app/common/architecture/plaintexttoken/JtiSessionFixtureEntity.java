package com.mannschaft.app.common.architecture.plaintexttoken;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * fixture（番人3 陰性の土台）: Refresh Token の形。トークン本体はハッシュ列、JTI は token 系でない名前の列。
 */
@Entity
@Table(name = "plaintext_guard_fixture_jti_session")
public class JtiSessionFixtureEntity {

    @Id
    private Long id;

    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    private String jti;

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }
}
