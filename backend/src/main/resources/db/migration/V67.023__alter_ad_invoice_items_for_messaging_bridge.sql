-- F09.17 Phase 11-b ε-C: ad_invoice_items を月次課金ブリッジに対応させる
-- V67.022 で messaging_campaign_id 列が追加済み。さらに以下を追加する:
--   * channel_type: ANNOUNCEMENT / EMAIL / PUSH / BANNER の課金行識別 (F09.17 由来)
--   * month_key: YYYY-MM 形式の集計対象月 (冪等用 UNIQUE 索引の構成要素)
--   * UNIQUE(messaging_campaign_id, channel_type, month_key): 月次バッチを 2 回流しても同じ請求行が二重に積まれない
--   * campaign_id (BIGINT) を NULL 許可化: F09.17 由来行は messaging_campaign_id 側で識別
--
-- 既存 F09.7 系の課金行はすべて campaign_id (BIGINT) NOT NULL のまま運用継続。
-- F09.17 由来行は campaign_id IS NULL AND messaging_campaign_id IS NOT NULL で識別する。
-- AdInvoiceItemEntity / MonthlyInvoiceBatchService の既存 NOT NULL 前提は維持される
-- (F09.7 経路は messaging_campaign_id を NULL のまま BIGINT campaign_id を入れるため)。

ALTER TABLE ad_invoice_items
    MODIFY COLUMN campaign_id BIGINT NULL
        COMMENT 'F09.7 ad_campaigns.id (NULL=F09.17 messaging_campaign_id 経由)',
    ADD COLUMN channel_type VARCHAR(20) NULL
        COMMENT 'F09.17 由来行のチャネル種別: ANNOUNCEMENT / EMAIL / PUSH / BANNER',
    ADD COLUMN month_key VARCHAR(7) NULL
        COMMENT 'F09.17 由来行の集計対象月 (YYYY-MM)';

-- 冪等性確保: 同月同チャネル同キャンペーンで 2 行作成を禁止
CREATE UNIQUE INDEX uq_aii_messaging_channel_month
    ON ad_invoice_items (messaging_campaign_id, channel_type, month_key);
