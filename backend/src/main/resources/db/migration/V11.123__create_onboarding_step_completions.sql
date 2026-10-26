CREATE TABLE onboarding_step_completions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    progress_id BIGINT UNSIGNED NOT NULL,
    step_id BIGINT UNSIGNED NOT NULL,
    completion_type VARCHAR(20) NOT NULL,
    completed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_osc_progress_step (progress_id, step_id),
    INDEX idx_osc_step (step_id),
    CONSTRAINT chk_osc_completion_type CHECK (completion_type IN ('MANUAL', 'AUTO_FORM', 'AUTO_KB_VIEW', 'AUTO_PROFILE', 'ADMIN_OVERRIDE')),
    CONSTRAINT fk_osc_progress FOREIGN KEY (progress_id) REFERENCES onboarding_progresses(id) ON DELETE CASCADE,
    CONSTRAINT fk_osc_step FOREIGN KEY (step_id) REFERENCES onboarding_template_steps(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
