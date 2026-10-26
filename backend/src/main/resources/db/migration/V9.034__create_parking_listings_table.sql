-- F09.3 譲渡希望
CREATE TABLE parking_listings (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    space_id              BIGINT UNSIGNED NOT NULL,
    assignment_id         BIGINT UNSIGNED NOT NULL,
    listed_by             BIGINT UNSIGNED NOT NULL,
    reason                VARCHAR(500)    NULL,
    desired_transfer_date DATE            NULL,
    status                ENUM('OPEN','RESERVED','TRANSFERRED','CANCELLED') NOT NULL DEFAULT 'OPEN',
    transferee_user_id    BIGINT UNSIGNED NULL,
    transferee_vehicle_id BIGINT UNSIGNED NULL,
    transferred_at        DATETIME        NULL,
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at            DATETIME        NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pl_space_active (space_id, status, deleted_at),
    INDEX idx_pl_scope (space_id),
    INDEX idx_pl_listed_by (listed_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
