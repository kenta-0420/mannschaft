-- F10.1 content_reports テーブルをモデレーション仕様に拡張
-- 既存の reporter_type/reporter_id を reported_by に統合し、スコープ・対象ユーザー・スナップショットを追加

ALTER TABLE content_reports
    ADD COLUMN reported_by BIGINT UNSIGNED NULL AFTER target_id,
    ADD COLUMN scope_type VARCHAR(20) NULL AFTER reported_by,
    ADD COLUMN scope_id BIGINT UNSIGNED NULL AFTER scope_type,
    ADD COLUMN target_user_id BIGINT UNSIGNED NULL AFTER scope_id,
    ADD COLUMN content_snapshot JSON NULL AFTER description,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;

-- 既存データのマイグレーション: reporter_id を reported_by にコピー
UPDATE content_reports SET reported_by = reporter_id WHERE reported_by IS NULL;

-- NOT NULL 制約を後から適用
ALTER TABLE content_reports
    MODIFY COLUMN reported_by BIGINT UNSIGNED NOT NULL,
    MODIFY COLUMN scope_type VARCHAR(20) NOT NULL DEFAULT 'TEAM',
    MODIFY COLUMN scope_id BIGINT UNSIGNED NOT NULL DEFAULT 0;

-- 旧カラム削除
ALTER TABLE content_reports
    DROP COLUMN reporter_type,
    DROP COLUMN reporter_id,
    DROP COLUMN identity_disclosed,
    DROP COLUMN resolved_at,
    DROP COLUMN review_note;

-- 新規インデックス追加
ALTER TABLE content_reports
    ADD INDEX idx_cr_scope (scope_type, scope_id, status, created_at DESC),
    ADD INDEX idx_cr_reported_by (reported_by),
    ADD INDEX idx_cr_target_user (target_user_id, status),
    ADD UNIQUE KEY uq_cr_target_reporter (target_type, target_id, reported_by);
