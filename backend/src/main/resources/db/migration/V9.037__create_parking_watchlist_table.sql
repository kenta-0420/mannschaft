-- F09.3 ウォッチリスト
CREATE TABLE parking_watchlist (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    scope_type  VARCHAR(20)     NOT NULL,
    scope_id    BIGINT UNSIGNED NOT NULL,
    space_type  ENUM('INDOOR','OUTDOOR','ACCESSIBLE','MOTORCYCLE','BICYCLE','OTHER') NULL,
    floor       VARCHAR(10)     NULL,
    max_price   DECIMAL(10,0)   NULL,
    is_active   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_pw_scope (scope_type, scope_id),
    INDEX idx_pw_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
