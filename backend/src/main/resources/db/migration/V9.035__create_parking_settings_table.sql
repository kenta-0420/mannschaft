-- F09.3 駐車場設定
CREATE TABLE parking_settings (
    id                                    BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type                            VARCHAR(20)      NOT NULL,
    scope_id                              BIGINT UNSIGNED  NOT NULL,
    max_spaces_per_user                   SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    max_visitor_reservations_per_day      SMALLINT UNSIGNED NOT NULL DEFAULT 2,
    visitor_reservation_max_days_ahead    SMALLINT UNSIGNED NOT NULL DEFAULT 30,
    visitor_reservation_requires_approval BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at                            DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                            DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_psettings_scope (scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
