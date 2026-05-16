-- F09.17 Phase 11-a: ad_invoice_items に messaging_campaign_id 列追加
-- F09.17 メッセージ型キャンペーン (BINARY(16) UUIDv7) を請求項目に紐付ける。
-- ad_messaging_campaigns へは FK を張らない (F09.7 既存 ad_invoice_items は BIGINT campaign_id を持つ別ドメイン参照のため)。
ALTER TABLE ad_invoice_items
    ADD COLUMN messaging_campaign_id BINARY(16) NULL
        COMMENT 'F09.17 ad_messaging_campaigns.id (NULL=F09.7 BIGINT campaign_id 経由の従来課金)',
    ADD INDEX idx_aii_messaging_campaign (messaging_campaign_id);
