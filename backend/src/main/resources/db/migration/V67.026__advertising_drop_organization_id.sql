-- F09.17 Phase 11-e: advertiser_accounts / ad_messaging_campaigns から organization_id 物理削除
--
-- Phase 11-d-1 (V67.024): scope_type / scope_id カラム追加 + backfill
-- Phase 11-d-1 (V67.025): scope_type / scope_id NOT NULL 昇格 + organization_id NULL 許可降格
-- Phase 11-e   (本 migration): organization_id 物理削除
--
-- advertiser_accounts では V10.060 で作成された FK fk_advertiser_accounts_organization を先に DROP する
-- 予定だったが、V62.005 (phase1a cross-domain FK 削除) で既に DROP 済みのため不要。
-- ad_messaging_campaigns の organization_id は FK 制約なし (INDEX のみ)。

-- advertiser_accounts: organization_id カラム DROP
-- NOTE: fk_advertiser_accounts_organization は V62.005 で既に DROP 済み
ALTER TABLE advertiser_accounts
    DROP COLUMN organization_id;

-- ad_messaging_campaigns: 旧インデックス + organization_id カラム DROP
ALTER TABLE ad_messaging_campaigns
    DROP INDEX idx_amc_org_status,
    DROP COLUMN organization_id;
