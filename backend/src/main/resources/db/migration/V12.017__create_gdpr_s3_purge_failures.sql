CREATE TABLE gdpr_s3_purge_failures (
    id            BINARY(16)          NOT NULL,
    user_id       BIGINT              NOT NULL,
    s3_key        VARCHAR(500)        NOT NULL,
    failed_at     DATETIME(6)         NOT NULL,
    retry_count   TINYINT UNSIGNED    NOT NULL DEFAULT 0,
    last_retried_at DATETIME(6),
    last_error    VARCHAR(500),
    resolved_at   DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_gdpr_s3_purge_failures_unresolved (resolved_at),
    INDEX idx_gdpr_s3_purge_failures_user_id   (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
