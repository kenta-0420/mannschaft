-- WebAuthn資格情報テーブル
CREATE TABLE webauthn_credentials (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    credential_id VARCHAR(500) NOT NULL,
    public_key TEXT NOT NULL,
    sign_count BIGINT NOT NULL DEFAULT 0,
    device_name VARCHAR(100) NULL,
    aaguid VARCHAR(36) NULL,
    last_used_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_webauthn_credentials_credential_id UNIQUE (credential_id),
    CONSTRAINT fk_webauthn_credentials_user FOREIGN KEY (user_id) REFERENCES users(id)
);
