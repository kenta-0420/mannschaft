-- F09.3 区画割り当て
CREATE TABLE parking_assignments (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    space_id            BIGINT UNSIGNED NOT NULL,
    vehicle_id          BIGINT UNSIGNED NULL,
    user_id             BIGINT UNSIGNED NOT NULL,
    assigned_by         BIGINT UNSIGNED NOT NULL,
    assigned_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    contract_start_date DATE            NULL,
    contract_end_date   DATE            NULL,
    released_at         DATETIME        NULL,
    released_by         BIGINT UNSIGNED NULL,
    release_reason      VARCHAR(200)    NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pa_space_active (space_id, released_at),
    INDEX idx_pa_vehicle (vehicle_id),
    INDEX idx_pa_user (user_id),
    INDEX idx_pa_space_history (space_id, assigned_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
