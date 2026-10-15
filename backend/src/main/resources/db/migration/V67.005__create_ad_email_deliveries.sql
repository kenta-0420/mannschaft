-- F09.17 Phase 11-a: メールチャネル配信実績
-- user_id / direct_mail_recipient_id はクロスドメイン参照のため FK なし
CREATE TABLE ad_email_deliveries (
    id                        BINARY(16)      NOT NULL,
    campaign_id               BINARY(16)      NOT NULL COMMENT 'ad_messaging_campaigns.id (FK CASCADE)',
    user_id                   BIGINT UNSIGNED NULL     COMMENT '受信者 (退会時 NULL 化・FKなし)',
    direct_mail_recipient_id  BIGINT UNSIGNED NOT NULL COMMENT 'F09.6 direct_mail_recipients.id (FKなし)',
    sent_at                   DATETIME        NOT NULL COMMENT 'SES 送信完了時刻',
    opened_at                 DATETIME        NULL     COMMENT '開封ピクセル発火時刻',
    bounced_at                DATETIME        NULL     COMMENT 'バウンス検知時刻',
    bounce_type               ENUM('HARD','SOFT','COMPLAINT') NULL COMMENT 'バウンス種別',
    month_key                 CHAR(7)         NOT NULL COMMENT 'YYYY-MM 形式 (パーティショニング用)',
    created_at                DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_aed_camp_month (campaign_id, month_key),
    INDEX idx_aed_user (user_id, sent_at),
    INDEX idx_aed_recipient (direct_mail_recipient_id),
    INDEX idx_aed_bounce (bounce_type, bounced_at),
    CONSTRAINT fk_aed_campaign FOREIGN KEY (campaign_id)
        REFERENCES ad_messaging_campaigns (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F09.17 メールチャネル配信実績';
