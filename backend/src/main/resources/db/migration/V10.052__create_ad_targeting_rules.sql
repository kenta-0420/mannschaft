CREATE TABLE ad_targeting_rules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT UNSIGNED NOT NULL,
    target_prefecture VARCHAR(20),
    target_template VARCHAR(30),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_campaign_id (campaign_id),
    CONSTRAINT fk_ad_targeting_campaign FOREIGN KEY (campaign_id) REFERENCES ad_campaigns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
