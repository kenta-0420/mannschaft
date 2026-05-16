-- F09.17 Phase 11-a: キャンペーンチャネル別本文・件名・クリエイティブ
-- campaign_id は同一ドメイン FK のため ON DELETE CASCADE 許可
-- banner_creative_id は F09.7 ads.id への参照のため FK なし
CREATE TABLE ad_messaging_campaign_channels (
    id                  BINARY(16)      NOT NULL,
    campaign_id         BINARY(16)      NOT NULL COMMENT 'ad_messaging_campaigns.id (同一ドメイン FK CASCADE)',
    channel_type        ENUM('ANNOUNCEMENT','EMAIL','PUSH','BANNER') NOT NULL COMMENT '配信チャネル',
    locale              VARCHAR(10)     NOT NULL DEFAULT 'ja' COMMENT '言語コード (ja/en/zh/ko/de/es)',
    subject             VARCHAR(200)    NULL     COMMENT 'EMAIL/PUSH 件名 ([PR] / 【広告】は配信時自動付与)',
    body_markdown       MEDIUMTEXT      NOT NULL COMMENT '本文 Markdown (F02.6 サニタイザ通過)',
    image_url           VARCHAR(500)    NULL     COMMENT 'バナー画像 URL (許可ホストのみ)',
    cta_label           VARCHAR(50)     NULL     COMMENT 'CTA ボタンラベル',
    cta_url             VARCHAR(500)    NULL     COMMENT 'CTA リンク (https のみ)',
    banner_creative_id  BIGINT UNSIGNED NULL     COMMENT 'F09.7 ads.id (BANNER 時のみ・FKなし)',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_amcc_camp_chan_locale (campaign_id, channel_type, locale),
    INDEX idx_amcc_banner (banner_creative_id),
    CONSTRAINT fk_amcc_campaign FOREIGN KEY (campaign_id)
        REFERENCES ad_messaging_campaigns (id) ON DELETE CASCADE,
    CONSTRAINT chk_amcc_banner CHECK (channel_type != 'BANNER' OR banner_creative_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 キャンペーンチャネル別コンテンツ (多言語対応)';
