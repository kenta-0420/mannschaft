-- CORE候補。V236/V238は不変。未適用・未検証。
-- UUIDv7/BINARY(16)、UTC DATETIME(6)、本人shardはowner同TX。

CREATE TABLE ranch_reward_policies (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version_number BIGINT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    schema_version INT NOT NULL,
    settings_json JSON NOT NULL,
    content_hash BINARY(32) NOT NULL,
    published_by BIGINT UNSIGNED NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_reward_policies_version (version_number),
    UNIQUE KEY uq_ranch_reward_policies_effective (effective_at),
    CONSTRAINT chk_ranch_reward_policies_positive CHECK (version_number > 0 AND schema_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_week_budgets (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    week_starts_on DATE NOT NULL,
    policy_id BINARY(16) NOT NULL,
    rule_snapshot JSON NOT NULL,
    global_cap BIGINT NOT NULL,
    awarded_total BIGINT NOT NULL,
    source_counts JSON NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_week_budgets_user_week (user_id, week_starts_on),
    KEY idx_ranch_week_budgets_owner_week (owner_id, week_starts_on),
    CONSTRAINT fk_ranch_week_budgets_owner FOREIGN KEY (owner_id) REFERENCES ranch_owners(id),
    CONSTRAINT chk_ranch_week_budgets_nonnegative CHECK (
        global_cap >= 0 AND awarded_total BETWEEN 0 AND global_cap AND version >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_reward_decisions (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    event_id BINARY(16) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    canonical_key_hash BINARY(32) NOT NULL,
    canonical_key VARBINARY(240) NOT NULL,
    reward_week DATE NOT NULL,
    policy_id BINARY(16) NULL,
    status VARCHAR(40) NOT NULL,
    requested_points BIGINT NOT NULL,
    awarded_points BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_reward_decisions_user_source_key (user_id, source_type, canonical_key_hash),
    UNIQUE KEY uq_ranch_reward_decisions_event (event_id),
    KEY idx_ranch_reward_decisions_user_decided_id (user_id, decided_at, id),
    CONSTRAINT fk_ranch_reward_decisions_owner FOREIGN KEY (owner_id) REFERENCES ranch_owners(id),
    CONSTRAINT chk_ranch_reward_decisions_status CHECK (status IN (
        'AWARDED', 'CAPPED', 'SOURCE_COUNT_CAPPED', 'SOURCE_DISABLED',
        'NOT_PARTICIPATING', 'REWARDS_PAUSED', 'INELIGIBLE'
    )),
    CONSTRAINT chk_ranch_reward_decisions_points CHECK (
        requested_points >= 0 AND awarded_points >= 0 AND awarded_points <= requested_points
        AND (policy_id IS NOT NULL OR (
            status IN ('SOURCE_DISABLED', 'NOT_PARTICIPATING', 'REWARDS_PAUSED', 'INELIGIBLE')
            AND requested_points = 0 AND awarded_points = 0
        ))
    ),
    CONSTRAINT chk_ranch_reward_decisions_key CHECK (OCTET_LENGTH(canonical_key) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
