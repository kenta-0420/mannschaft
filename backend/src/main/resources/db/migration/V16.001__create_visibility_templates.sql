CREATE TABLE visibility_templates (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT UNSIGNED NULL,
    name VARCHAR(60) NOT NULL,
    description VARCHAR(240) NULL,
    icon_emoji VARCHAR(16) NULL,
    is_system_preset BOOLEAN NOT NULL DEFAULT FALSE,
    preset_key VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_vt_owner (owner_user_id),
    UNIQUE KEY uk_vt_preset_key (preset_key),
    UNIQUE KEY uk_vt_owner_name (owner_user_id, name),
    CONSTRAINT fk_vt_owner FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_vt_preset_owner CHECK (
        (is_system_preset = TRUE AND owner_user_id IS NULL AND preset_key IS NOT NULL)
        OR
        (is_system_preset = FALSE AND owner_user_id IS NOT NULL AND preset_key IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
