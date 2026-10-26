CREATE TABLE ranking_snapshots (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type VARCHAR(50) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    period_type VARCHAR(20) NOT NULL,
    period_label VARCHAR(20) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    total_points INT NOT NULL DEFAULT 0,
    rank_position INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_rs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_rs_scope_period_user UNIQUE (scope_type, scope_id, period_type, period_label, user_id),
    INDEX idx_rs_scope_period (scope_type, scope_id, period_type, period_label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
