-- F09.5 共用施設予約: 時間帯別料金
CREATE TABLE facility_time_rates (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    facility_id   BIGINT UNSIGNED NOT NULL,
    day_type      ENUM('WEEKDAY','WEEKEND','HOLIDAY') NOT NULL,
    time_from     TIME            NOT NULL,
    time_to       TIME            NOT NULL,
    rate_per_slot DECIMAL(10,0)   NOT NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_ftr_facility_day_time (facility_id, day_type, time_from),
    CONSTRAINT fk_ftr_facility FOREIGN KEY (facility_id) REFERENCES shared_facilities(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
