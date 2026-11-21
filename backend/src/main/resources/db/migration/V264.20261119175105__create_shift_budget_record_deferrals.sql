-- CMP-260909-2152 α: 遅延記録（締めの PREPARED を理由に記録・取消ができなかったシフト単位の行）。所有者＋リースで確保する。
-- pending_key は PENDING の間だけ締めイベント ID を持つ生成列（シフト×イベントごとに PENDING は1件）。
CREATE TABLE shift_budget_record_deferrals (
    id BINARY(16) NOT NULL,
    shift_id BIGINT UNSIGNED NOT NULL,
    close_event_id BINARY(16) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    claimed_by VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    pending_key BINARY(16) GENERATED ALWAYS AS (CASE WHEN state = 'PENDING' THEN close_event_id END) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sbrd_shift_pending (shift_id, pending_key),
    KEY idx_sbrd_event_state (close_event_id, state),
    KEY idx_sbrd_state_created (state, created_at),
    CONSTRAINT chk_sbrd_state CHECK (state IN ('PENDING', 'REPLAYED', 'REDEFERRED')),
    CONSTRAINT chk_sbrd_lease CHECK ((claimed_by IS NULL) = (lease_until IS NULL)),
    CONSTRAINT fk_sbrd_event FOREIGN KEY (close_event_id) REFERENCES shift_budget_monthly_close_events (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
