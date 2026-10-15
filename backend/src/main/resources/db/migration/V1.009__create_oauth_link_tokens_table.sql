-- OAuthアカウント連携トークンテーブル
CREATE TABLE oauth_link_tokens (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    provider_email VARCHAR(255) NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_oauth_link_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_oauth_link_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);
