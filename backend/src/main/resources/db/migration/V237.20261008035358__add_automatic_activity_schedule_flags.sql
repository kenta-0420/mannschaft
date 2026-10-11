-- 既存活動は手動記録のまま維持し、予定の過去データを自動生成しない。
ALTER TABLE activity_results
    ADD COLUMN is_auto_generated_from_schedule BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN is_planned BOOLEAN NOT NULL DEFAULT FALSE;
