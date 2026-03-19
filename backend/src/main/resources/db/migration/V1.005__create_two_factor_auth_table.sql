-- 二要素認証テーブル
CREATE TABLE two_factor_auth (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    totp_secret VARCHAR(500) NOT NULL,
    backup_codes JSON NOT NULL,
    is_enabled TINYINT(1) NOT NULL DEFAULT 0,
    verified_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_two_factor_auth_user UNIQUE (user_id),
    CONSTRAINT fk_two_factor_auth_user FOREIGN KEY (user_id) REFERENCES users(id)
);
