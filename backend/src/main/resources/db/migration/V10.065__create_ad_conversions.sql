CREATE TABLE ad_conversions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    click_id BIGINT UNSIGNED NOT NULL,
    campaign_id BIGINT UNSIGNED NOT NULL,
    ad_id BIGINT UNSIGNED NOT NULL,
    conversion_type ENUM('TEAM_JOIN','ORG_JOIN','MODULE_ACTIVATE','EVENT_REGISTER') NOT NULL,
    converted_user_id BIGINT UNSIGNED NOT NULL,
    converted_at DATETIME NOT NULL,
    attribution_window_days TINYINT UNSIGNED NOT NULL DEFAULT 7,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_click_conversion (click_id, conversion_type),
    INDEX idx_campaign_converted (campaign_id, converted_at),
    INDEX idx_click_id (click_id),
    INDEX idx_ad_converted (ad_id, converted_at),
    CONSTRAINT fk_ad_conversions_user FOREIGN KEY (converted_user_id) REFERENCES users(id)
    -- FK to ad_clicks, ad_campaigns, ads deferred until F09.7 expansion tables
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
