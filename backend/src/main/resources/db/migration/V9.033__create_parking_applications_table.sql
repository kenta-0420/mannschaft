-- F09.3 区画申請
CREATE TABLE parking_applications (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    space_id         BIGINT UNSIGNED NOT NULL,
    user_id          BIGINT UNSIGNED NOT NULL,
    vehicle_id       BIGINT UNSIGNED NOT NULL,
    source_type      ENUM('VACANCY','LISTING') NOT NULL DEFAULT 'VACANCY',
    listing_id       BIGINT UNSIGNED NULL,
    status           ENUM('PENDING','APPROVED','REJECTED','CANCELLED','LOTTERY_PENDING') NOT NULL DEFAULT 'PENDING',
    priority         SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    message          VARCHAR(500)    NULL,
    rejection_reason VARCHAR(500)    NULL,
    lottery_number   INT UNSIGNED    NULL,
    decided_at       DATETIME        NULL,
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX uq_papp_space_user_source (space_id, user_id, source_type),
    INDEX idx_papp_space_status (space_id, status),
    INDEX idx_papp_user (user_id),
    INDEX idx_papp_lottery (space_id, lottery_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
