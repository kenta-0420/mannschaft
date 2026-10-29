-- M 専有の通知予約。業務 payload / PII / 例外本文を保存しない。
-- WAITING_RECOVERY は同 TX の復旧 intent 予約。relay は PENDING のみを取得する。
-- claim_token は RELAYED 後も保持し、retention の世代比較に使う。
-- RELAYED だけでは削除不可。参照中・未解決・DEAD の自動削除は行わない。
CREATE TABLE module_event_notice_outbox (
    id BINARY(16) NOT NULL,
    notice_ref BIGINT NOT NULL AUTO_INCREMENT,
    notice_key BINARY(16) NOT NULL,
    resolver_key VARCHAR(100) NOT NULL,
    operation_id BINARY(16) NOT NULL,
    episode_id BINARY(16) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    scope_id BIGINT NOT NULL,
    organization_id BIGINT NULL,
    phase VARCHAR(16) NOT NULL,
    audience VARCHAR(32) NOT NULL,
    descriptor_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    claim_token BINARY(16) NULL,
    claimed_at DATETIME(6) NULL,
    relayed_at DATETIME(6) NULL,
    dead_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_module_event_notice_ref (notice_ref),
    UNIQUE KEY uk_module_event_notice_key (notice_key),
    KEY idx_module_event_notice_ready (status, next_attempt_at, id),
    KEY idx_module_event_notice_lease (status, claimed_at, id),
    KEY idx_module_event_notice_retention (status, relayed_at, notice_ref),
    KEY idx_module_event_notice_episode (resolver_key, operation_id, episode_id),
    CONSTRAINT chk_module_event_notice_scope CHECK (scope_id > 0 AND (organization_id IS NULL OR organization_id > 0)),
    CONSTRAINT chk_module_event_notice_attempt CHECK (attempt_count >= 0 AND descriptor_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='M 通知の耐久予約と relay lease';
