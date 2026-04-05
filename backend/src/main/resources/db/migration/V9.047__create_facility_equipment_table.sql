-- F09.5 共用施設予約: 付帯備品
CREATE TABLE facility_equipment (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    facility_id     BIGINT UNSIGNED NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    description     VARCHAR(500)    NULL,
    total_quantity  SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    price_per_use   DECIMAL(10,0)   NULL,
    is_available    BOOLEAN         NOT NULL DEFAULT TRUE,
    display_order   SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME        NULL,

    INDEX idx_fe_facility (facility_id),
    UNIQUE KEY uq_fe_facility_name (facility_id, name, deleted_at),
    CONSTRAINT fk_fe_facility FOREIGN KEY (facility_id) REFERENCES shared_facilities(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
