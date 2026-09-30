-- ALL_SCOPES は発動元設定を triggered_by_setting_id に残し、適用範囲を GLOBAL で表す。
ALTER TABLE recruitment_user_penalties
    MODIFY COLUMN scope_type ENUM('TEAM', 'ORGANIZATION', 'GLOBAL') NOT NULL,
    MODIFY COLUMN scope_id BIGINT UNSIGNED NULL;

ALTER TABLE recruitment_user_penalties
    ADD CONSTRAINT chk_rup_global_scope
        CHECK ((scope_type = 'GLOBAL' AND scope_id IS NULL)
            OR (scope_type <> 'GLOBAL' AND scope_id IS NOT NULL)),
    ADD COLUMN active_scope_key BIGINT UNSIGNED
        GENERATED ALWAYS AS (COALESCE(scope_id, 0)) STORED,
    ADD COLUMN active_marker TINYINT
        GENERATED ALWAYS AS (CASE WHEN lifted_at IS NULL THEN 1 ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_rup_active_one (user_id, scope_type, active_scope_key, active_marker),
    ADD INDEX idx_rup_user_active (user_id, lifted_at, expires_at);
