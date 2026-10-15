CREATE TABLE point_transactions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(50) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    point_rule_id BIGINT UNSIGNED NULL,
    transaction_type ENUM('EARN', 'RESET', 'ADMIN_ADJUST') NOT NULL,
    points INT NOT NULL,
    action_type VARCHAR(50) NULL,
    reference_type VARCHAR(50) NULL,
    reference_id BIGINT UNSIGNED NULL,
    earned_on DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_pt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_pt_point_rule FOREIGN KEY (point_rule_id) REFERENCES point_rules(id) ON DELETE SET NULL,
    INDEX idx_pt_user_scope_date (user_id, scope_type, scope_id, earned_on),
    INDEX idx_pt_scope_date (scope_type, scope_id, earned_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
