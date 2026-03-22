-- content_reports_archive スキーマを V10.001 適用後の content_reports に合わせる
-- 旧カラム削除 + 新カラム追加

ALTER TABLE content_reports_archive
    ADD COLUMN reported_by     BIGINT UNSIGNED NOT NULL AFTER target_id,
    ADD COLUMN scope_type      VARCHAR(20)     NOT NULL DEFAULT 'TEAM' AFTER reported_by,
    ADD COLUMN scope_id        BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER scope_type,
    ADD COLUMN target_user_id  BIGINT UNSIGNED NULL AFTER scope_id,
    ADD COLUMN content_snapshot JSON           NULL AFTER description,
    ADD COLUMN reviewed_at     DATETIME        NULL AFTER reviewed_by,
    ADD COLUMN updated_at      DATETIME        NOT NULL AFTER created_at;

-- 既存アーカイブデータのマイグレーション
UPDATE content_reports_archive SET reported_by = reporter_id WHERE reported_by = 0;

ALTER TABLE content_reports_archive
    DROP COLUMN reporter_type,
    DROP COLUMN reporter_id,
    DROP COLUMN identity_disclosed,
    DROP COLUMN resolved_at,
    DROP COLUMN review_note;
