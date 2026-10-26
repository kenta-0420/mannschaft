-- F09.3 区画料金履歴
CREATE TABLE parking_space_price_history (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    space_id    BIGINT UNSIGNED NOT NULL,
    old_price   DECIMAL(10,0)   NULL,
    new_price   DECIMAL(10,0)   NULL,
    changed_by  BIGINT UNSIGNED NOT NULL,
    changed_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_psph_space (space_id, changed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
