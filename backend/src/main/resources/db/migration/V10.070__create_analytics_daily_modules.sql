CREATE TABLE analytics_daily_modules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    date DATE NOT NULL,
    module_id BIGINT UNSIGNED NOT NULL,
    active_teams INT UNSIGNED NOT NULL DEFAULT 0,
    new_activations INT UNSIGNED NOT NULL DEFAULT 0,
    deactivations INT UNSIGNED NOT NULL DEFAULT 0,
    revenue DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_date_module (date, module_id),
    INDEX idx_module_id (module_id),
    CONSTRAINT fk_analytics_daily_modules_module FOREIGN KEY (module_id) REFERENCES module_definitions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
