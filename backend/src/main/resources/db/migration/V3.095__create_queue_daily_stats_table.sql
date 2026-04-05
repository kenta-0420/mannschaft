CREATE TABLE queue_daily_stats (
    id                  BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type          VARCHAR(20)      NOT NULL,
    scope_id            BIGINT UNSIGNED  NOT NULL,
    counter_id          BIGINT UNSIGNED,
    stat_date           DATE             NOT NULL,
    total_tickets       SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    completed_count     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    cancelled_count     SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    no_show_count       SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    avg_wait_minutes    DECIMAL(5,1),
    avg_service_minutes DECIMAL(5,1),
    peak_hour           TINYINT UNSIGNED,
    qr_count            SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    online_count        SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_qds_counter_date (counter_id, stat_date),

    CONSTRAINT fk_qds_counter FOREIGN KEY (counter_id) REFERENCES queue_counters (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='順番待ち日次統計';
