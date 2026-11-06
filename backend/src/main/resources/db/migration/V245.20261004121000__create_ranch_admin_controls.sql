-- CORE候補: 運営公開制御・報酬停止期間・care公開版・管理成功command。
-- V236/V238/V244を変更せず追加する。本文・個人profile・本番catalog seedは置かない。
CREATE TABLE ranch_operational_controls (
    id TINYINT NOT NULL,
    is_care_enabled BOOLEAN NOT NULL,
    is_shop_enabled BOOLEAN NOT NULL,
    is_delivery_paused BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    updated_by BIGINT UNSIGNED NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_ranch_operational_controls_singleton CHECK (id = 1),
    CONSTRAINT chk_ranch_operational_controls_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO ranch_operational_controls (
    id, is_care_enabled, is_shop_enabled, is_delivery_paused,
    version, updated_by, created_at, updated_at
) VALUES (1, FALSE, FALSE, TRUE, 0, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

CREATE TABLE ranch_reward_pause_periods (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NULL,
    reason_code VARCHAR(40) NOT NULL,
    changed_by BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ranch_reward_pause_periods_start (starts_at, id),
    CONSTRAINT chk_ranch_reward_pause_periods_positive CHECK (
        ends_at IS NULL OR ends_at > starts_at
    ),
    CONSTRAINT chk_ranch_reward_pause_periods_reason CHECK (OCTET_LENGTH(reason_code) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_care_rules (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version_number BIGINT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    amount_xp BIGINT NOT NULL,
    weekly_cap_xp BIGINT NOT NULL,
    juvenile_xp BIGINT NOT NULL,
    adult_xp BIGINT NOT NULL,
    content_hash BINARY(32) NOT NULL,
    published_by BIGINT UNSIGNED NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_care_rules_version (version_number),
    UNIQUE KEY uq_ranch_care_rules_effective (effective_at),
    CONSTRAINT chk_ranch_care_rules_growth CHECK (
        version_number > 0 AND amount_xp > 0 AND weekly_cap_xp > 0
        AND juvenile_xp > 0 AND adult_xp > juvenile_xp
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_admin_commands (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    idempotency_key BINARY(16) NOT NULL,
    command_type VARCHAR(30) NOT NULL,
    body_hash BINARY(32) NOT NULL,
    result_json JSON NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_admin_commands_actor_key (actor_user_id, idempotency_key),
    KEY idx_ranch_admin_commands_actor_complete (actor_user_id, completed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
