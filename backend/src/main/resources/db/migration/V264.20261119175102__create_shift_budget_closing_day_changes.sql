-- CMP-260909-2152 α: 締め日の変更履歴（追記専用。UPDATE/DELETE の経路は無い）。
CREATE TABLE shift_budget_closing_day_changes (
    id BINARY(16) NOT NULL,
    setting_id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    revision INT NOT NULL,
    effective_period_key CHAR(7) NOT NULL,
    closing_day TINYINT UNSIGNED NOT NULL,
    previous_closing_day TINYINT UNSIGNED NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sbcdc_setting_revision (setting_id, revision),
    KEY idx_sbcdc_org_effective (organization_id, effective_period_key),
    CONSTRAINT chk_sbcdc_closing_day CHECK (closing_day BETWEEN 0 AND 28 AND previous_closing_day BETWEEN 0 AND 28),
    CONSTRAINT fk_sbcdc_setting FOREIGN KEY (setting_id) REFERENCES shift_budget_closing_settings (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
