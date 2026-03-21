CREATE TABLE queue_counters (
    id                          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    category_id                 BIGINT UNSIGNED  NOT NULL,
    name                        VARCHAR(100)     NOT NULL,
    description                 VARCHAR(500),
    accept_mode                 VARCHAR(20)      NOT NULL DEFAULT 'BOTH',
    avg_service_minutes         SMALLINT UNSIGNED NOT NULL DEFAULT 10,
    avg_service_minutes_manual  BOOLEAN          NOT NULL DEFAULT FALSE,
    max_queue_size              SMALLINT UNSIGNED NOT NULL DEFAULT 50,
    is_active                   BOOLEAN          NOT NULL DEFAULT TRUE,
    is_accepting                BOOLEAN          NOT NULL DEFAULT TRUE,
    operating_time_from         TIME,
    operating_time_to           TIME,
    display_order               SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_by                  BIGINT UNSIGNED,
    created_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME,

    PRIMARY KEY (id),
    INDEX idx_qcnt_category_order (category_id, display_order),

    CONSTRAINT fk_qcnt_category FOREIGN KEY (category_id) REFERENCES queue_categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_qcnt_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='順番待ちカウンター';
