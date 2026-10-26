CREATE TABLE point_rules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type VARCHAR(50) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    points INT NOT NULL,
    daily_limit INT NOT NULL DEFAULT 0,
    is_system TINYINT(1) NOT NULL DEFAULT 0,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_pr_scope_action UNIQUE (scope_type, scope_id, action_type, deleted_at),
    INDEX idx_pr_scope_active (scope_type, scope_id, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
