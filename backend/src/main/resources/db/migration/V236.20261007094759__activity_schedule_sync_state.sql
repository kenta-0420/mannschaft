-- 既存記録の基準NULLを維持し、予定由来の基本項目を確認同期する。
ALTER TABLE activity_results
    ADD COLUMN activity_end_date DATE NULL,
    ADD COLUMN schedule_sync_state JSON NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
