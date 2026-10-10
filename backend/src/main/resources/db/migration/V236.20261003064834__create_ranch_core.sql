-- 本人専用牧場の状態・命名・無料care・成功コマンドを同ドメインで保存する。
-- usersへのFKは設けず、認証ドメインの行ロック窓口でACTIVE状態を保証する。

CREATE TABLE ranch_owners (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL,
    balance BIGINT NOT NULL,
    view_mode VARCHAR(20) NOT NULL,
    render_style VARCHAR(20) NOT NULL,
    motion_mode VARCHAR(20) NOT NULL,
    is_sound_enabled BOOLEAN NOT NULL,
    sound_volume INT NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_owners_user_id (user_id),
    CONSTRAINT chk_ranch_owners_1 CHECK (balance >= 0 AND version >= 0 AND sound_volume BETWEEN 0 AND 100),
    CONSTRAINT chk_ranch_owners_2 CHECK (status IN ('ACTIVE','PAUSED')),
    CONSTRAINT chk_ranch_owners_3 CHECK (view_mode = 'ROOM'),
    CONSTRAINT chk_ranch_owners_4 CHECK (render_style IN ('PIXEL','PAINT_2D')),
    CONSTRAINT chk_ranch_owners_5 CHECK (motion_mode IN ('NORMAL','REDUCED','STOPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_dinosaurs (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    habitat VARCHAR(8) NULL,
    species_key VARCHAR(60) NULL,
    variant_key VARCHAR(32) NULL,
    species_catalog_version BIGINT NULL,
    assignment_method VARCHAR(30) NULL,
    selection_confirmed_at DATETIME(6) NULL,
    assignment_rule_version VARCHAR(80) NULL,
    assignment_result_id BINARY(16) NULL,
    assignment_input_hash VARCHAR(64) NULL,
    egg_started_at DATETIME(6) NOT NULL,
    egg_ready_at DATETIME(6) NOT NULL,
    egg_rule_snapshot JSON NOT NULL,
    hatched_at DATETIME(6) NULL,
    name VARCHAR(160) NULL,
    named_at DATETIME(6) NULL,
    stage VARCHAR(20) NOT NULL,
    xp BIGINT NOT NULL,
    growth_rule_snapshot JSON NOT NULL,
    affinity BIGINT NOT NULL,
    affinity_rule_snapshot JSON NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_dinosaurs_owner_id (owner_id),
    KEY idx_ranch_dinosaurs_user_id (user_id),
    CONSTRAINT fk_ranch_dinosaurs_owners FOREIGN KEY (owner_id) REFERENCES ranch_owners(id),
    CONSTRAINT chk_ranch_dinosaurs_1 CHECK (xp >= 0 AND affinity >= 0 AND version >= 0),
    CONSTRAINT chk_ranch_dinosaurs_2 CHECK (habitat IS NULL OR habitat IN ('LAND','SEA','AIR')),
    CONSTRAINT chk_ranch_dinosaurs_3 CHECK (stage IN ('EGG','BABY','JUVENILE','ADULT')),
    CONSTRAINT chk_ranch_dinosaurs_4 CHECK ((stage = 'EGG' AND hatched_at IS NULL AND name IS NULL AND named_at IS NULL) OR (stage <> 'EGG' AND hatched_at IS NOT NULL AND name IS NOT NULL AND named_at = hatched_at AND selection_confirmed_at IS NOT NULL)),
    CONSTRAINT chk_ranch_dinosaurs_5 CHECK (name IS NULL OR OCTET_LENGTH(name) <= 512),
    CONSTRAINT chk_ranch_dinosaurs_6 CHECK ((selection_confirmed_at IS NULL AND species_key IS NULL AND variant_key IS NULL AND species_catalog_version IS NULL AND assignment_method IS NULL) OR (selection_confirmed_at IS NOT NULL AND species_key IS NOT NULL AND variant_key IS NOT NULL AND habitat IS NOT NULL AND species_catalog_version IS NOT NULL AND assignment_method IS NOT NULL)),
    CONSTRAINT chk_ranch_dinosaurs_7 CHECK (egg_ready_at >= egg_started_at),
    CONSTRAINT chk_ranch_dinosaurs_8 CHECK (assignment_method IS NULL OR assignment_method IN ('HABITAT_RANDOM','BIRTH_STYLE','DIAGNOSIS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_commands (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    idempotency_key BINARY(16) NOT NULL,
    command_type VARCHAR(30) NOT NULL,
    body_hash BINARY(32) NOT NULL,
    result_json JSON NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_commands_user_id_idempotency_key (user_id,idempotency_key),
    KEY idx_ranch_commands_owner_id_completed_at (owner_id, completed_at),
    CONSTRAINT fk_ranch_commands_owners FOREIGN KEY (owner_id) REFERENCES ranch_owners(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_participation_periods (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_ranch_participation_periods_user_id_starts_at (user_id, starts_at),
    CONSTRAINT fk_ranch_participation_periods_owners FOREIGN KEY (owner_id) REFERENCES ranch_owners(id),
    CONSTRAINT chk_ranch_participation_periods_1 CHECK (ends_at IS NULL OR ends_at >= starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_room_placements (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    slot_key VARCHAR(30) NOT NULL,
    inventory_id BINARY(16) NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_room_placements_owner_id_slot_key (owner_id, slot_key),
    UNIQUE KEY uq_ranch_room_placements_owner_id_inventory_id (owner_id, inventory_id),
    KEY idx_ranch_room_placements_user_id (user_id),
    CONSTRAINT fk_ranch_room_placements_owners FOREIGN KEY (owner_id) REFERENCES ranch_owners(id),
    CONSTRAINT chk_ranch_room_placements_1 CHECK (slot_key IN ('SHELF_1','SHELF_2','SHELF_3')),
    CONSTRAINT chk_ranch_room_placements_2 CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_care_week_budgets (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    week_starts_on DATE NOT NULL,
    rule_id BINARY(16) NOT NULL,
    rule_snapshot JSON NOT NULL,
    weekly_cap_xp BIGINT NOT NULL,
    awarded_xp BIGINT NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_care_week_budgets_user_id_week (user_id, week_starts_on),
    KEY idx_ranch_care_week_budgets_owner_id_week (owner_id, week_starts_on),
    CONSTRAINT fk_ranch_care_week_budgets_owners FOREIGN KEY (owner_id) REFERENCES ranch_owners(id),
    CONSTRAINT chk_ranch_care_week_budgets_1 CHECK (weekly_cap_xp > 0 AND awarded_xp BETWEEN 0 AND weekly_cap_xp AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_affinity_units (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    dinosaur_id BINARY(16) NOT NULL,
    earned_on DATE NOT NULL,
    kind VARCHAR(10) NOT NULL,
    gain BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_affinity_units_user_id_dinosaur_id_day_kind (user_id, dinosaur_id, earned_on, kind),
    CONSTRAINT fk_ranch_affinity_units_owners FOREIGN KEY (owner_id) REFERENCES ranch_owners(id),
    CONSTRAINT chk_ranch_affinity_units_1 CHECK (kind IN ('FEED','TOUCH')),
    CONSTRAINT chk_ranch_affinity_units_2 CHECK (gain > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ranch_point_ledger (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    decision_id BINARY(16) NULL,
    command_id BINARY(16) NULL,
    entry_kind VARCHAR(20) NOT NULL,
    delta_points BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    delta_xp BIGINT NOT NULL,
    dinosaur_id BINARY(16) NULL,
    rule_snapshot JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ranch_point_ledger_decision_id (decision_id),
    UNIQUE KEY uq_ranch_point_ledger_user_command (user_id,command_id),
    KEY idx_ranch_point_ledger_user_occurred_id (user_id,occurred_at,id),
    CONSTRAINT fk_ranch_point_ledger_owner FOREIGN KEY (owner_id) REFERENCES ranch_owners(id),
    CONSTRAINT chk_ranch_point_ledger_balance CHECK (balance_after >= 0),
    CONSTRAINT chk_ranch_point_ledger_kind CHECK (
        (entry_kind='REWARD' AND decision_id IS NOT NULL AND command_id IS NULL AND delta_points>0 AND delta_xp=0)
        OR (entry_kind='CARE' AND command_id IS NOT NULL AND decision_id IS NULL AND dinosaur_id IS NOT NULL AND delta_points=0 AND delta_xp>=0)
        OR (entry_kind='PURCHASE' AND command_id IS NOT NULL AND decision_id IS NULL AND delta_points<0 AND delta_xp=0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
