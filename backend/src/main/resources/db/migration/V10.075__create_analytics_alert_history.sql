CREATE TABLE analytics_alert_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rule_id BIGINT UNSIGNED NOT NULL,
    triggered_at DATETIME NOT NULL,
    metric_value DECIMAL(12,4) NOT NULL,
    threshold_value DECIMAL(12,4) NOT NULL,
    comparison_value DECIMAL(12,4) DEFAULT NULL,
    change_pct DECIMAL(8,4) DEFAULT NULL,
    notified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rule_triggered (rule_id, triggered_at),
    CONSTRAINT fk_analytics_alert_history_rule FOREIGN KEY (rule_id) REFERENCES analytics_alert_rules(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
