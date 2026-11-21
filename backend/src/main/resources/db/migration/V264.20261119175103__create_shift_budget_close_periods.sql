-- CMP-260909-2152 α: shift_budget_close_periods（組織の締め期間の確定。初回の締めで境界を固定する。期間キー＝締め日が属する月）。
CREATE TABLE shift_budget_close_periods (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    period_key CHAR(7) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    closing_day TINYINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sbcp_org_key (organization_id, period_key),
    UNIQUE KEY uq_sbcp_org_start (organization_id, period_start),
    UNIQUE KEY uq_sbcp_org_end (organization_id, period_end),
    CONSTRAINT chk_sbcp_range CHECK (period_start <= period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
