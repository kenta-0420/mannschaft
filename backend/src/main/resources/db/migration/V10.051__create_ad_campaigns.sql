CREATE TABLE ad_campaigns (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    advertiser_organization_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(200) NOT NULL,
    status ENUM('DRAFT','PENDING_REVIEW','ACTIVE','PAUSED','ENDED') NOT NULL DEFAULT 'DRAFT',
    pricing_model ENUM('CPM','CPC') NOT NULL,
    daily_budget DECIMAL(10,2),
    daily_impression_limit INT UNSIGNED,
    start_date DATE,
    end_date DATE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_org_status (advertiser_organization_id, status),
    CONSTRAINT fk_ad_campaigns_org FOREIGN KEY (advertiser_organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
