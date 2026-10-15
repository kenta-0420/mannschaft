CREATE TABLE budget_allocations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    fiscal_year_id BIGINT UNSIGNED NOT NULL,
    category_id BIGINT UNSIGNED NOT NULL,
    amount DECIMAL(12,0) NOT NULL,
    note VARCHAR(500) DEFAULT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_ba_fiscal_category UNIQUE (fiscal_year_id, category_id),
    CONSTRAINT chk_ba_amount CHECK (amount >= 0),
    CONSTRAINT fk_ba_fiscal_year FOREIGN KEY (fiscal_year_id) REFERENCES budget_fiscal_years(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ba_category FOREIGN KEY (category_id) REFERENCES budget_categories(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
