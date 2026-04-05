CREATE TABLE ad_daily_stats (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT UNSIGNED NOT NULL,
    ad_id BIGINT UNSIGNED NOT NULL,
    date DATE NOT NULL,
    impressions BIGINT UNSIGNED NOT NULL DEFAULT 0,
    clicks BIGINT UNSIGNED NOT NULL DEFAULT 0,
    cost DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_ad_date (campaign_id, ad_id, date),
    INDEX idx_campaign_date (campaign_id, date),
    INDEX idx_ad_date (ad_id, date),
    CONSTRAINT fk_ad_daily_stats_campaign FOREIGN KEY (campaign_id) REFERENCES ad_campaigns(id),
    CONSTRAINT fk_ad_daily_stats_ad FOREIGN KEY (ad_id) REFERENCES ads(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
