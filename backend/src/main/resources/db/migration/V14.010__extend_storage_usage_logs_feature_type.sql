-- F13 Phase 4-α: storage_usage_logs.feature_type の拡張
--
-- feature_type は VARCHAR(30) のため DDL 変更は不要。
-- 本マイグレーションはコメント更新と整合性チェックのみを行う。
-- Java enum (com.mannschaft.app.common.storage.quota.StorageFeatureType) に
-- PERSONAL_TIMETABLE_NOTES / SCHEDULE_MEDIA を追加することが本対応の本体。
--
-- 設計書: docs/cross-cutting/storage_quota.md §11 Phase 4-α 救出手順
--
-- 想定される feature_type の値（Phase 4-α 時点）:
--   FILE_SHARING / CIRCULATION / BULLETIN / CHAT / TIMELINE / CMS / GALLERY
--   PERSONAL_TIMETABLE_NOTES (新規・F03.15)
--   SCHEDULE_MEDIA           (新規・F03.14、Phase 4-γ で利用予定)
--
-- カラムコメントを更新（既存値はそのまま、列挙値の運用上のドキュメント反映）
ALTER TABLE storage_usage_logs
    MODIFY COLUMN feature_type VARCHAR(30) NOT NULL
    COMMENT '機能種別: FILE_SHARING / CIRCULATION / BULLETIN / CHAT / TIMELINE / CMS / GALLERY / PERSONAL_TIMETABLE_NOTES / SCHEDULE_MEDIA';
