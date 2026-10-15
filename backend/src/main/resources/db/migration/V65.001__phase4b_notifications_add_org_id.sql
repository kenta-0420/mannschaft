-- Phase 4-B: notifications に organization_id を追加（テナントシャーディング布石）
-- クロスドメイン FK は作らない（CLAUDE.md §DB設計原則1）。インデックスのみ追加し、整合性はアプリ層で保証する。
ALTER TABLE notifications
    ADD COLUMN organization_id BIGINT NULL AFTER user_id,
    ADD INDEX idx_notifications_org_created (organization_id, created_at DESC);
