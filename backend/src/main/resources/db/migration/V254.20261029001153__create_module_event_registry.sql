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
    resolver_key VARCHAR(100) NULL,
    operation_id BINARY(16) NULL,
    episode_id BINARY(16) NULL,
    scope_type VARCHAR(16) NULL,
    scope_id BIGINT UNSIGNED NULL,
    organization_id BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_event_publication_attempts_publication_id UNIQUE (publication_id),
    KEY idx_event_publication_attempts_status_lease_until (status, lease_until),
    KEY idx_event_publication_attempts_episode
        (resolver_key, operation_id, episode_id, scope_type, scope_id, organization_id),
    CONSTRAINT chk_event_publication_attempts_impact_binding CHECK (
        (resolver_key IS NULL AND operation_id IS NULL AND episode_id IS NULL
            AND scope_type IS NULL AND scope_id IS NULL AND organization_id IS NULL)
        OR (resolver_key IS NOT NULL AND resolver_key <> ''
            AND operation_id IS NOT NULL AND episode_id IS NOT NULL AND scope_type IS NOT NULL
            AND scope_id IS NOT NULL AND scope_id > 0 AND scope_type IN ('TEAM','ORGANIZATION')
            AND (organization_id IS NULL OR organization_id > 0)
            AND (scope_type = 'TEAM' OR (organization_id IS NOT NULL AND organization_id = scope_id)))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
