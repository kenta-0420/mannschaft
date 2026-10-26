-- F09.19.5 (Contract): ad_campaigns の旧カラム・インデックス削除（V147 Expand の後段）。
-- 正本: docs/features/F09.19_ad_slot_serving.md §5.2（V144.006 相当）。
-- ※ クロスドメイン FK fk_ad_campaigns_org は V62.005（phase1a wave5）で撤廃済み。
--   ここで DROP FOREIGN KEY を書くと本番で「制約が存在しない」エラーになるため書かない。
--   Expand 適用 + アプリ参照切替（MonthlyInvoiceBatchService 等）完了後に適用する。
ALTER TABLE ad_campaigns
    DROP INDEX idx_org_status,
    DROP COLUMN advertiser_organization_id;
