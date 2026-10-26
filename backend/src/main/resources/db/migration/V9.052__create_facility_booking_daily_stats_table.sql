-- F09.5 共用施設予約: 日次統計
CREATE TABLE facility_booking_daily_stats (
    id                   BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    scope_type           VARCHAR(20)     NOT NULL,
    scope_id             BIGINT UNSIGNED NOT NULL,
    facility_id          BIGINT UNSIGNED NOT NULL,
    stat_date            DATE            NOT NULL,
    booking_count        INT UNSIGNED    NOT NULL DEFAULT 0,
    completed_count      INT UNSIGNED    NOT NULL DEFAULT 0,
    no_show_count        INT UNSIGNED    NOT NULL DEFAULT 0,
    cancelled_count      INT UNSIGNED    NOT NULL DEFAULT 0,
    revenue_total        DECIMAL(10,0)   NOT NULL DEFAULT 0,
    revenue_stripe       DECIMAL(10,0)   NOT NULL DEFAULT 0,
    revenue_direct       DECIMAL(10,0)   NOT NULL DEFAULT 0,
    platform_fee_total   DECIMAL(10,0)   NOT NULL DEFAULT 0,
    slot_count_booked    INT UNSIGNED    NOT NULL DEFAULT 0,
    slot_count_available INT UNSIGNED    NOT NULL DEFAULT 0,
    stay_nights_total    INT UNSIGNED    NOT NULL DEFAULT 0,
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uq_fbds_scope_facility_date (scope_type, scope_id, facility_id, stat_date),
    INDEX idx_fbds_scope_date (scope_type, scope_id, stat_date),
    CONSTRAINT fk_fbds_facility FOREIGN KEY (facility_id) REFERENCES shared_facilities(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
