CREATE TABLE ad_invoice_items (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    invoice_id BIGINT UNSIGNED NOT NULL,
    campaign_id BIGINT UNSIGNED NOT NULL,
    campaign_name VARCHAR(200) NOT NULL,
    pricing_model ENUM('CPM','CPC') NOT NULL,
    impressions BIGINT UNSIGNED NOT NULL DEFAULT 0,
    clicks BIGINT UNSIGNED NOT NULL DEFAULT 0,
    unit_price DECIMAL(10,4) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_invoice_id (invoice_id),
    CONSTRAINT fk_ad_invoice_items_invoice FOREIGN KEY (invoice_id) REFERENCES ad_invoices(id)
    -- FK to ad_campaigns deferred until F09.7 expansion tables are created
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
