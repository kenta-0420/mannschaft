CREATE TABLE user_calendar_sync_settings (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id    BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(20)     NOT NULL,
    scope_id   BIGINT UNSIGNED NOT NULL,
    is_enabled BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ucss_user_scope (user_id, scope_type, scope_id),
    INDEX idx_ucss_scope (scope_type, scope_id, is_enabled),
    CONSTRAINT fk_ucss_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='チーム・組織別カレンダー同期設定';
