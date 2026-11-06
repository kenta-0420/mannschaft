package com.mannschaft.app.common.architecture.plaintexttoken;

import jakarta.persistence.MappedSuperclass;

/**
 * fixture（番人1 陽性）: {@code @MappedSuperclass} に宣言された平文の秘密列。
 * 番人1は Entity 本体だけでなく MappedSuperclass の宣言フィールドも検査する。
 */
@MappedSuperclass
public abstract class PlaintextSecretFixtureSuperclass {

    private String secret;

    public String getSecret() {
        return secret;
    }
}
