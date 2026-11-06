package com.mannschaft.app.common.architecture.plaintexttoken;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * fixture（番人1 陰性）: 合言葉をハッシュだけで保存している Entity（列名が {@code _hash} で終わる）。
 */
@Entity
@Table(name = "plaintext_guard_fixture_hashed")
public class HashedTokenFixtureEntity {

    @Id
    private Long id;

    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    private String correlationId;

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
