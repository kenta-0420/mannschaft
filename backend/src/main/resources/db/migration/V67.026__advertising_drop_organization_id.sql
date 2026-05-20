-- F09.17 Phase 11-e: advertiser_accounts / ad_messaging_campaigns から organization_id 物理削除
--
-- Phase 11-d-1 (V67.024): scope_type / scope_id カラム追加 + backfill
-- Phase 11-d-1 (V67.025): scope_type / scope_id NOT NULL 昇格 + organization_id NULL 許可降格
-- Phase 11-e   (本 migration): organization_id 物理削除
--
-- advertiser_accounts では V10.060 で作成された FK fk_advertiser_accounts_organization を先に DROP する。
-- ad_messaging_campaigns の organization_id は FK 制約なし (INDEX のみ)。

-- advertiser_accounts: FK + organization_id カラム DROP
ALTER TABLE advertiser_accounts
    DROP FOREIGN KEY fk_advertiser_accounts_organization,
    DROP COLUMN organization_id;

-- ad_messaging_campaigns: 旧インデックス + organization_id カラム DROP
ALTER TABLE ad_messaging_campaigns
    DROP INDEX idx_amc_org_status,
    DROP COLUMN organization_id;
