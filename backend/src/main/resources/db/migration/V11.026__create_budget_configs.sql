CREATE TABLE budget_configs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    approval_threshold DECIMAL(12,0) DEFAULT NULL,
    workflow_template_id BIGINT UNSIGNED DEFAULT NULL,
    auto_record_payments BOOLEAN NOT NULL DEFAULT TRUE,
    default_income_category_id BIGINT UNSIGNED DEFAULT NULL,
    budget_warning_threshold SMALLINT NOT NULL DEFAULT 80,
    budget_critical_threshold SMALLINT NOT NULL DEFAULT 95,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_bconf_scope UNIQUE (scope_type, scope_id),
    CONSTRAINT fk_bconf_workflow_template FOREIGN KEY (workflow_template_id) REFERENCES workflow_templates(id) ON DELETE SET NULL,
    CONSTRAINT fk_bconf_default_income_category FOREIGN KEY (default_income_category_id) REFERENCES budget_categories(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
