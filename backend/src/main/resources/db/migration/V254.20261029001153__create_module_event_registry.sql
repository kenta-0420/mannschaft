-- L1-M: 公式 Registry の6列と、アプリ所有の試行台帳を作成する。
-- 本表の UUID はライブラリが採番し、補助表は UuidV7Entity が独立採番する。
CREATE TABLE event_publication (
    completion_date DATETIME(6) NULL,
    publication_date DATETIME(6) NOT NULL,
    id BINARY(16) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    listener_id VARCHAR(255) NOT NULL,
    serialized_event VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_publication_attempts (
    id BINARY(16) NOT NULL,
    publication_id BINARY(16) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    claim_token BINARY(16) NOT NULL,
    last_attempt_at DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    quarantined_at DATETIME(6) NULL,
    failure_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_event_publication_attempts_publication_id UNIQUE (publication_id),
    KEY idx_event_publication_attempts_status_lease_until (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
