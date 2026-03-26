-- F10.1 通知配信統計テーブル
CREATE TABLE notification_delivery_stats (
    id              BIGINT UNSIGNED         NOT NULL AUTO_INCREMENT,
    date            DATE           NOT NULL,
    channel         VARCHAR(20)    NOT NULL,
    sent_count      INT UNSIGNED   NOT NULL DEFAULT 0,
    delivered_count INT UNSIGNED   NOT NULL DEFAULT 0,
    failed_count    INT UNSIGNED   NOT NULL DEFAULT 0,
    bounce_count    INT UNSIGNED   NOT NULL DEFAULT 0,
    created_at      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_nds_date_channel (date, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
