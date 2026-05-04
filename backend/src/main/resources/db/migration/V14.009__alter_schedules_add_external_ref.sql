-- F03.15 Phase 4: schedules テーブルに external_ref 列を追加し、idempotency 用の UNIQUE INDEX を貼る。
-- external_ref は JSON 文字列を格納する想定（例: {"source":"F03.15","timetable_change_id":1,"personal_timetable_slot_id":2}）。
-- TEXT 列は MySQL 8.0 の制約で直接 UNIQUE INDEX を張れないため、MD5 ハッシュを STORED generated column として保持し、
-- そちらに UNIQUE INDEX を貼る。

ALTER TABLE schedules
    ADD COLUMN external_ref TEXT NULL COMMENT 'F03.15 等の外部参照 JSON。idempotency 用',
    ADD COLUMN external_ref_hash CHAR(32) GENERATED ALWAYS AS (
        CASE WHEN external_ref IS NULL THEN NULL ELSE MD5(external_ref) END
    ) STORED COMMENT 'external_ref の MD5 ハッシュ。UNIQUE INDEX 用';

CREATE UNIQUE INDEX uq_sch_external_ref_hash ON schedules (external_ref_hash);
