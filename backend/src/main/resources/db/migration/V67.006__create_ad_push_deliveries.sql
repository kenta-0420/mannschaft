-- F09.17 Phase 11-a: プッシュ通知チャネル配信実績
-- user_id / notification_id はクロスドメイン参照のため FK なし
CREATE TABLE ad_push_deliveries (
    id              BINARY(16)      NOT NULL,
    campaign_id     BINARY(16)      NOT NULL COMMENT 'ad_messaging_campaigns.id (FK CASCADE)',
    user_id         BIGINT UNSIGNED NULL     COMMENT '受信者 (退会時 NULL 化・FKなし)',
    notification_id BIGINT UNSIGNED NOT NULL COMMENT 'F04.3 notifications.id (FKなし)',
    delivered_at    DATETIME        NOT NULL COMMENT 'FCM/APNs 配信成功時刻',
    tapped_at       DATETIME        NULL     COMMENT 'タップ時刻',
    failed_reason   VARCHAR(100)    NULL     COMMENT '失敗理由 (unregistered 等)',
    month_key       CHAR(7)         NOT NULL COMMENT 'YYYY-MM 形式 (パーティショニング用)',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_apd_camp_month (campaign_id, month_key),
    INDEX idx_apd_user (user_id, delivered_at),
    INDEX idx_apd_notification (notification_id),
    CONSTRAINT fk_apd_campaign FOREIGN KEY (campaign_id)
        REFERENCES ad_messaging_campaigns (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 プッシュチャネル配信実績';
