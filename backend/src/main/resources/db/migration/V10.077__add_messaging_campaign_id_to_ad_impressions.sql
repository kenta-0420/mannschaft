-- F09.7 / F09.17 型不一致根治: ad_impressions に messaging_campaign_id (BINARY(16)) を追加する。
-- 既存の campaign_id (BIGINT) は F09.7 の ad_campaigns.id 用としてそのまま残す。
-- F09.17 メッセージ型キャンペーン (ad_messaging_campaigns.id, UUID) はこちらに記録する。
-- また、F09.17 用ルートでは campaign_id が不要なため NULL を許容するよう変更する。

ALTER TABLE ad_impressions
    MODIFY COLUMN campaign_id BIGINT UNSIGNED NULL AFTER ad_id,
    ADD COLUMN messaging_campaign_id BINARY(16) NULL AFTER campaign_id;

CREATE INDEX idx_ad_impressions_messaging_campaign_id ON ad_impressions (messaging_campaign_id);
