-- F09.3 来場者予約テンプレート（定期）
CREATE TABLE parking_visitor_recurring (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id              BIGINT UNSIGNED NOT NULL,
    space_id             BIGINT UNSIGNED NOT NULL,
    scope_type           VARCHAR(20)     NOT NULL,
    scope_id             BIGINT UNSIGNED NOT NULL,
    recurrence_type      ENUM('WEEKLY','BIWEEKLY','MONTHLY') NOT NULL,
    day_of_week          TINYINT UNSIGNED NULL,
    day_of_month         TINYINT UNSIGNED NULL,
    time_from            TIME            NOT NULL,
    time_to              TIME            NOT NULL,
    visitor_name         VARCHAR(100)    NULL,
    visitor_plate_number VARCHAR(30)     NULL,
    purpose              VARCHAR(200)    NULL,
    is_active            BOOLEAN         NOT NULL DEFAULT TRUE,
    next_generate_date   DATE            NOT NULL,
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_pvrc_user (user_id),
    INDEX idx_pvrc_generate (next_generate_date, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
