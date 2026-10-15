CREATE TABLE onboarding_template_steps (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(1000) DEFAULT NULL,
    step_type VARCHAR(20) NOT NULL,
    reference_id BIGINT UNSIGNED DEFAULT NULL,
    reference_url VARCHAR(500) DEFAULT NULL,
    deadline_offset_days SMALLINT DEFAULT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_ots_template (template_id, sort_order),
    CONSTRAINT chk_ots_step_type CHECK (step_type IN ('MANUAL', 'URL', 'FORM', 'KNOWLEDGE_BASE', 'PROFILE_COMPLETION')),
    CONSTRAINT fk_ots_template FOREIGN KEY (template_id) REFERENCES onboarding_templates(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
