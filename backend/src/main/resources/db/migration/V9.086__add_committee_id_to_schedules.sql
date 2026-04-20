-- F04.10: schedules に committee_id を追加し、XOR CHECK 制約を 4 カラムに拡張する
ALTER TABLE schedules
    ADD COLUMN committee_id BIGINT UNSIGNED NULL AFTER user_id
        COMMENT 'FK → committees（ON DELETE CASCADE）委員会スコープ時にセット',
    ADD CONSTRAINT fk_schedules_committee FOREIGN KEY (committee_id) REFERENCES committees (id) ON DELETE CASCADE,
    DROP CHECK chk_schedule_scope,
    ADD CONSTRAINT ck_schedules_scope_xor CHECK (
        (CASE WHEN team_id IS NOT NULL THEN 1 ELSE 0 END)
        + (CASE WHEN organization_id IS NOT NULL THEN 1 ELSE 0 END)
        + (CASE WHEN user_id IS NOT NULL THEN 1 ELSE 0 END)
        + (CASE WHEN committee_id IS NOT NULL THEN 1 ELSE 0 END)
        = 1
    );

CREATE INDEX idx_schedules_committee ON schedules (committee_id, start_at);
