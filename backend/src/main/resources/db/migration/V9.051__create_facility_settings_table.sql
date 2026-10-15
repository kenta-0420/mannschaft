-- F09.5 共用施設予約: スコープ別設定
CREATE TABLE facility_settings (
    id                            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    scope_type                    VARCHAR(20)     NOT NULL,
    scope_id                      BIGINT UNSIGNED NOT NULL,
    requires_approval             BOOLEAN         NOT NULL DEFAULT TRUE,
    max_bookings_per_day_per_user SMALLINT UNSIGNED NOT NULL DEFAULT 2,
    allow_stripe_payment          BOOLEAN         NOT NULL DEFAULT FALSE,
    cancellation_deadline_hours   SMALLINT UNSIGNED NOT NULL DEFAULT 24,
    no_show_penalty_enabled       BOOLEAN         NOT NULL DEFAULT FALSE,
    no_show_penalty_threshold     SMALLINT UNSIGNED NOT NULL DEFAULT 3,
    no_show_penalty_days          SMALLINT UNSIGNED NOT NULL DEFAULT 30,
    created_at                    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_fs_scope (scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
