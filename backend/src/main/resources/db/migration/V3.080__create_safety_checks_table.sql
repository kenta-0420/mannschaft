CREATE TABLE safety_checks (
    id                        BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type                VARCHAR(20)      NOT NULL,
    scope_id                  BIGINT UNSIGNED  NOT NULL,
    title                     VARCHAR(200),
    message                   VARCHAR(1000),
    is_drill                  BOOLEAN          NOT NULL DEFAULT FALSE,
    status                    VARCHAR(20)      NOT NULL DEFAULT 'ACTIVE',
    reminder_interval_minutes INT,
    last_reminder_at          DATETIME,
    total_target_count        INT              NOT NULL DEFAULT 0,
    admin_24h_notified        BOOLEAN          NOT NULL DEFAULT FALSE,
    bulletin_thread_id        BIGINT UNSIGNED  NULL,
    created_by                BIGINT UNSIGNED,
    closed_at                 DATETIME,
    closed_by                 BIGINT UNSIGNED,
    created_at                DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_sc_scope_created (scope_type, scope_id, created_at DESC),
    INDEX idx_sc_status (status),

    CONSTRAINT fk_sc_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_sc_closed_by FOREIGN KEY (closed_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='緊急安否確認マスター';
