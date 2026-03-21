CREATE TABLE queue_settings (
    id                          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type                  VARCHAR(20)      NOT NULL,
    scope_id                    BIGINT UNSIGNED  NOT NULL,
    no_show_timeout_minutes     SMALLINT UNSIGNED NOT NULL DEFAULT 5,
    no_show_penalty_enabled     BOOLEAN          NOT NULL DEFAULT FALSE,
    no_show_penalty_threshold   SMALLINT UNSIGNED NOT NULL DEFAULT 3,
    no_show_penalty_days        SMALLINT UNSIGNED NOT NULL DEFAULT 14,
    max_active_tickets_per_user SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    allow_guest_queue           BOOLEAN          NOT NULL DEFAULT TRUE,
    almost_ready_threshold      SMALLINT UNSIGNED NOT NULL DEFAULT 3,
    hold_extension_minutes      SMALLINT UNSIGNED NOT NULL DEFAULT 5,
    auto_adjust_service_minutes BOOLEAN          NOT NULL DEFAULT FALSE,
    display_board_public        BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_qset_scope (scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='順番待ち設定';
