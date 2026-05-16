-- F09.17 Phase 11-a: お知らせチャネル配信実績
-- user_id / announcement_feed_id はクロスドメイン参照のため FK なし
-- 退会時は user_id を NULL に SET (アプリ層でイベント駆動)
CREATE TABLE ad_announcement_deliveries (
    id                    BINARY(16)      NOT NULL,
    campaign_id           BINARY(16)      NOT NULL COMMENT 'ad_messaging_campaigns.id (FK CASCADE)',
    user_id               BIGINT UNSIGNED NULL     COMMENT '受信者 (退会時 NULL 化・FKなし)',
    announcement_feed_id  BIGINT UNSIGNED NOT NULL COMMENT 'F02.6 announcement_feeds.id (FKなし)',
    delivered_at          DATETIME        NOT NULL COMMENT '配信時刻',
    read_at               DATETIME        NULL     COMMENT '既読時刻',
    month_key             CHAR(7)         NOT NULL COMMENT 'YYYY-MM 形式 (パーティショニング用)',
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_aad_camp_month (campaign_id, month_key),
    INDEX idx_aad_user (user_id, delivered_at),
    INDEX idx_aad_feed (announcement_feed_id),
    CONSTRAINT fk_aad_campaign FOREIGN KEY (campaign_id)
        REFERENCES ad_messaging_campaigns (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 お知らせチャネル配信実績';
