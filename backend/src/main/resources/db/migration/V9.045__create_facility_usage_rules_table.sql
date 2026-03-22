-- F09.5 共用施設予約: 利用ルール
CREATE TABLE facility_usage_rules (
    id                          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    facility_id                 BIGINT UNSIGNED NOT NULL,
    max_hours_per_booking       DECIMAL(3,1)    NOT NULL DEFAULT 4.0,
    min_hours_per_booking       DECIMAL(3,1)    NOT NULL DEFAULT 0.5,
    max_bookings_per_month_per_user SMALLINT UNSIGNED NOT NULL DEFAULT 4,
    max_consecutive_slots       SMALLINT UNSIGNED NOT NULL DEFAULT 8,
    min_advance_hours           SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    max_advance_days            SMALLINT UNSIGNED NOT NULL DEFAULT 30,
    max_stay_nights             SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    cancellation_deadline_hours SMALLINT UNSIGNED NULL,
    available_time_from         TIME            NOT NULL DEFAULT '09:00:00',
    available_time_to           TIME            NOT NULL DEFAULT '22:00:00',
    available_days_of_week      JSON            NOT NULL DEFAULT '[0,1,2,3,4,5,6]',
    blackout_dates              JSON            NULL,
    notes                       VARCHAR(500)    NULL,
    created_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_fur_facility (facility_id),
    CONSTRAINT fk_fur_facility FOREIGN KEY (facility_id) REFERENCES shared_facilities(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
