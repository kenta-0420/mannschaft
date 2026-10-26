-- F09.17 Phase 11-a: バナーチャネル配信実績
-- user_id / ad_impression_id はクロスドメイン参照のため FK なし
CREATE TABLE ad_banner_deliveries (
    id                BINARY(16)      NOT NULL,
    campaign_id       BINARY(16)      NOT NULL COMMENT 'ad_messaging_campaigns.id (FK CASCADE)',
    user_id           BIGINT UNSIGNED NULL     COMMENT '受信者 (退会時 NULL 化・FKなし)',
    ad_impression_id  BIGINT UNSIGNED NOT NULL COMMENT 'F09.7 ad_impressions.id (FKなし)',
    served_at         DATETIME        NOT NULL COMMENT 'バナー表示時刻',
    clicked_at        DATETIME        NULL     COMMENT 'クリック時刻',
    month_key         CHAR(7)         NOT NULL COMMENT 'YYYY-MM 形式 (パーティショニング用)',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_abd_camp_month (campaign_id, month_key),
    INDEX idx_abd_user (user_id, served_at),
    INDEX idx_abd_impression (ad_impression_id),
    CONSTRAINT fk_abd_campaign FOREIGN KEY (campaign_id)
        REFERENCES ad_messaging_campaigns (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 バナーチャネル配信実績';
