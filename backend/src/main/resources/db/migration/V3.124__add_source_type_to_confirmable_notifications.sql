-- F03.11 Phase 3 前提: confirmable_notifications に source_type を追加
-- V13.006 (テーブル作成) の後に実行されること
-- 既存レコードは 'EMERGENCY_CLOSURE' として扱われる
ALTER TABLE confirmable_notifications
    ADD COLUMN source_type VARCHAR(40) NOT NULL DEFAULT 'EMERGENCY_CLOSURE' AFTER id;

CREATE INDEX idx_cn_source_status ON confirmable_notifications(source_type, status);
