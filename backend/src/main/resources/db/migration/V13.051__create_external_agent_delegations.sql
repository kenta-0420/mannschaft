-- F08.8 Phase 1: 管理会社（外部エージェント）への機能別委任
-- 区分所有法上の管理会社業務委託契約を電子化。
CREATE TABLE external_agent_delegations (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    agent_user_id BIGINT UNSIGNED NOT NULL, -- users.id（管理会社担当者・FKなし）
    agent_company_name VARCHAR(200) NOT NULL,
    delegation_type VARCHAR(40) NOT NULL,
    granted_by BIGINT UNSIGNED NOT NULL, -- users.id（理事長・FKなし）
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_by BIGINT UNSIGNED NULL,
    revoked_at DATETIME NULL,
    valid_until DATETIME NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_ead_scope_type CHECK (scope_type IN ('ORGANIZATION','TEAM'))
);

CREATE INDEX idx_ead_organization_id ON external_agent_delegations (organization_id);
CREATE INDEX idx_ead_scope_type ON external_agent_delegations (scope_type, scope_id, delegation_type, revoked_at);
CREATE INDEX idx_ead_agent ON external_agent_delegations (agent_user_id, revoked_at);
CREATE INDEX idx_ead_valid_until ON external_agent_delegations (valid_until);
