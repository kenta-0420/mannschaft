-- F05.3 電子印鑑: seal_scope_defaults テーブル
CREATE TABLE seal_scope_defaults (
    id          BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    scope_type  VARCHAR(20)  NOT NULL DEFAULT 'DEFAULT',
    scope_id    BIGINT UNSIGNED       NULL,
    seal_id     BIGINT UNSIGNED       NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seal_scope_defaults_user_scope (user_id, scope_type, scope_id),
    CONSTRAINT fk_seal_scope_defaults_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_seal_scope_defaults_seal FOREIGN KEY (seal_id) REFERENCES electronic_seals(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
