-- F09.17 Phase 11-d-1: 広告 scope ベース化（Expand + Backfill）
--
-- 既存 advertiser_accounts / ad_messaging_campaigns は organization_id 直結だったが、
-- チーム単位の広告キャンペーンを運用可能にするため監査ログと同様の
-- scope_type ENUM + scope_id BIGINT 2 カラム方式に変更する。
--
-- 本マイグレーションは Expand→Migrate→Contract の Expand + Backfill フェーズ。
-- - scope_type / scope_id を NULL 許可で追加
-- - 既存行は organization_id を scope_id にコピーし scope_type='ORGANIZATION' で backfill
-- - インデックスを追加
--
-- NOT NULL 昇格 / organization_id NULL 許可降格は V67.025 で行う。

-- advertiser_accounts
ALTER TABLE advertiser_accounts
    ADD COLUMN scope_type VARCHAR(20) NULL COMMENT 'スコープ種別 ORGANIZATION/TEAM (FKなし)',
    ADD COLUMN scope_id BIGINT UNSIGNED NULL COMMENT 'スコープ ID (organization_id または team_id・FKなし)';

UPDATE advertiser_accounts
SET scope_type = 'ORGANIZATION', scope_id = organization_id
WHERE scope_type IS NULL;

CREATE INDEX idx_aa_scope ON advertiser_accounts(scope_type, scope_id);

-- ad_messaging_campaigns
ALTER TABLE ad_messaging_campaigns
    ADD COLUMN scope_type VARCHAR(20) NULL COMMENT 'スコープ種別 ORGANIZATION/TEAM (FKなし)',
    ADD COLUMN scope_id BIGINT UNSIGNED NULL COMMENT 'スコープ ID (organization_id または team_id・FKなし)';

UPDATE ad_messaging_campaigns
SET scope_type = 'ORGANIZATION', scope_id = organization_id
WHERE scope_type IS NULL;

CREATE INDEX idx_amc_scope_status ON ad_messaging_campaigns(scope_type, scope_id, status, deleted_at);
