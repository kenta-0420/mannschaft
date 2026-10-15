CREATE TABLE user_badges (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    badge_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    earned_on DATE NOT NULL,
    period_label VARCHAR(20) NULL,
    awarded_by VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ub_badge FOREIGN KEY (badge_id) REFERENCES badges(id) ON DELETE CASCADE,
    CONSTRAINT fk_ub_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_ub_badge_user_period UNIQUE (badge_id, user_id, period_label),
    INDEX idx_ub_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
