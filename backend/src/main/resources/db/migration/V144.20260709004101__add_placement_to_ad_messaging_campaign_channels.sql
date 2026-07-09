-- F09.19.2 (§5.1 #3): ad_messaging_campaign_channels に BANNER チャネルの掲載面 placement 列を追加する。
--
-- placement は AdPlacement 語彙（§3 統一語彙・ads.placement と一致）。BANNER チャネル以外は NULL。
-- サービング（SpotlightServingService.addReservationCandidates）が ch.placement で掲載面を絞り込むため、
-- BANNER 予約バナーの配信対象を掲載面単位で解決できるようにする。
-- 付与単位はクリエイティブ（ads）だが、チャネルにも denormalize して配信クエリの絞り込みを効率化する（§3・§5.1）。
--
-- 既存 F09.17 チャネル行（第1弾時点で予約型配信は将来 §13）は placement=NULL のまま影響なし（後方互換）。

ALTER TABLE ad_messaging_campaign_channels
    ADD COLUMN placement VARCHAR(30) NULL
        COMMENT 'F09.19.2 BANNER チャネルの掲載面（AdPlacement 語彙・ads.placement と一致・BANNER 時のみ）'
        AFTER banner_creative_id;

CREATE INDEX idx_amcc_channel_placement ON ad_messaging_campaign_channels (channel_type, placement);
