CREATE TABLE ad_clicks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ad_id BIGINT UNSIGNED NOT NULL,
    campaign_id BIGINT UNSIGNED NOT NULL,
    impression_id BIGINT UNSIGNED,
    user_id BIGINT UNSIGNED,
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_campaign_occurred (campaign_id, occurred_at),
    INDEX idx_ad_occurred (ad_id, occurred_at),
    CONSTRAINT fk_ad_clicks_ad FOREIGN KEY (ad_id) REFERENCES ads(id),
    CONSTRAINT fk_ad_clicks_campaign FOREIGN KEY (campaign_id) REFERENCES ad_campaigns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
