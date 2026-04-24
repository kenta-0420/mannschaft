CREATE TABLE visibility_template_rules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id BIGINT UNSIGNED NOT NULL,
    rule_type VARCHAR(40) NOT NULL,
    rule_target_id BIGINT NULL,
    rule_target_text VARCHAR(120) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_vtr_template (template_id),
    INDEX idx_vtr_type_target (rule_type, rule_target_id),
    CONSTRAINT fk_vtr_template FOREIGN KEY (template_id) REFERENCES visibility_templates(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
