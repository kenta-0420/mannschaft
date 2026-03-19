-- メールアドレス変更トークンテーブル
CREATE TABLE email_change_tokens (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    new_email VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_email_change_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_email_change_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);
