-- F10.1 バッチジョブログテーブル
CREATE TABLE batch_job_logs (
    id               BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    job_name         VARCHAR(100) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    started_at       DATETIME     NOT NULL,
    finished_at      DATETIME     NULL,
    processed_count  INT          NOT NULL DEFAULT 0,
    error_message    TEXT         NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_bjl_job (job_name, started_at DESC),
    INDEX idx_bjl_status (status, started_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
