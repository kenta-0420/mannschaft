CREATE TABLE IF NOT EXISTS translation_assignments
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type VARCHAR(50) NOT NULL,
    scope_id   BIGINT UNSIGNED NOT NULL,
    user_id    BIGINT UNSIGNED NOT NULL,
    language   VARCHAR(10) NOT NULL,
    is_active  TINYINT(1)  NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_ta_scope_user_lang UNIQUE (scope_type, scope_id, user_id, language),
    CONSTRAINT fk_transassign_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_ta_scope (scope_type, scope_id, is_active),
    INDEX idx_ta_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
