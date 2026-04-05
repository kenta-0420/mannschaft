-- F10.1 メンテナンススケジュールテーブル
CREATE TABLE maintenance_schedules (
    id          BIGINT UNSIGNED        NOT NULL AUTO_INCREMENT,
    title       VARCHAR(200)  NOT NULL,
    message     TEXT          NULL,
    mode        VARCHAR(20)   NOT NULL DEFAULT 'MAINTENANCE',
    starts_at   DATETIME      NOT NULL,
    ends_at     DATETIME      NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'SCHEDULED',
    created_by  BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_ms_status (status, starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
