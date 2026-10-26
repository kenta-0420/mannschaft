CREATE TABLE ad_rate_cards (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    target_prefecture VARCHAR(20) NULL,
    target_template VARCHAR(30) NULL,
    pricing_model ENUM('CPM', 'CPC') NOT NULL,
    unit_price DECIMAL(10,4) NOT NULL,
    min_daily_budget DECIMAL(10,2) NOT NULL DEFAULT 500,
    effective_from DATE NOT NULL,
    effective_until DATE NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ad_rate_cards_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT chk_ad_rate_cards_unit_price CHECK (unit_price > 0),
    CONSTRAINT chk_ad_rate_cards_min_daily_budget CHECK (min_daily_budget >= 100)
);
CREATE INDEX idx_ad_rate_cards_effective ON ad_rate_cards(effective_from, effective_until);
