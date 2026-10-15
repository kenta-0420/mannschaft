-- F09.19.1: ad_messaging_campaign_channels の BANNER チャネルに placement を追加する（正本 §5.2 V144.003）
-- CHECK 追加は必ず「既存データ是正（UPDATE）→ 制約追加」の順（feedback_flyway_existing_data_check_drop）。
-- 既存 BANNER 行を 'DASHBOARD_TILE' に backfill してから chk_amcc_banner_placement を追加する。
ALTER TABLE ad_messaging_campaign_channels
    ADD COLUMN placement VARCHAR(30) NULL
        COMMENT 'AdPlacement。channel_type=BANNER 時のみ必須' AFTER banner_creative_id;

UPDATE ad_messaging_campaign_channels SET placement = 'DASHBOARD_TILE' WHERE channel_type = 'BANNER';

ALTER TABLE ad_messaging_campaign_channels
    ADD CONSTRAINT chk_amcc_banner_placement CHECK (channel_type != 'BANNER' OR placement IS NOT NULL);
