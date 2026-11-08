package com.mannschaft.app.common.architecture.plaintexttoken;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * fixture: {@link PlaintextSecretFixtureSuperclass} を継承する Entity。
 * 番人2の陽性（継承したフィールド {@code secret} を平文で検索する Repository）の対象になる。
 */
@Entity
@Table(name = "plaintext_guard_fixture_sub")
public class PlaintextSecretSubFixtureEntity extends PlaintextSecretFixtureSuperclass {

    @Id
    private Long id;

    public Long getId() {
        return id;
    }
}
