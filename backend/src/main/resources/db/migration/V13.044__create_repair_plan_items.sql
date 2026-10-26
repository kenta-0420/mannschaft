-- F08.8 Phase 1: 修繕計画項目（個別マンションの30年長期修繕計画）
-- repair_plan_templates と同ドメインのため FK 許容（ON DELETE SET NULL）。
-- linked_work_package_id は F09.13 へのクロスドメイン参照のため FK なし。
CREATE TABLE repair_plan_items (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    template_id BINARY(16) NULL,
    category VARCHAR(60) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    planned_year SMALLINT UNSIGNED NOT NULL,
    planned_month TINYINT UNSIGNED NULL,
    estimated_amount BIGINT UNSIGNED NOT NULL,
    cpi_inflation_basis_year SMALLINT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    linked_work_package_id BIGINT UNSIGNED NULL, -- F09.13 property_work_packages.id (FKなし)
    tags JSON NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    updated_by BIGINT UNSIGNED NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_rpi_template FOREIGN KEY (template_id)
        REFERENCES repair_plan_templates (id) ON DELETE SET NULL,
    CONSTRAINT chk_rpi_scope_type CHECK (scope_type IN ('ORGANIZATION','TEAM')),
    CONSTRAINT chk_rpi_status CHECK (status IN ('PLANNED','IN_PROGRESS','COMPLETED','DEFERRED','CANCELED')),
    CONSTRAINT chk_rpi_planned_year CHECK (planned_year BETWEEN 2000 AND 2100),
    CONSTRAINT chk_rpi_planned_month CHECK (planned_month IS NULL OR planned_month BETWEEN 1 AND 12),
    CONSTRAINT chk_rpi_estimated_amount CHECK (estimated_amount >= 0)
);

CREATE INDEX idx_rpi_organization_id ON repair_plan_items (organization_id);
CREATE INDEX idx_rpi_scope_year ON repair_plan_items (scope_type, scope_id, planned_year, status, deleted_at);
CREATE INDEX idx_rpi_category ON repair_plan_items (scope_type, scope_id, category, deleted_at);
CREATE INDEX idx_rpi_status ON repair_plan_items (scope_type, scope_id, status, deleted_at);
CREATE INDEX idx_rpi_linked_package ON repair_plan_items (linked_work_package_id);
CREATE INDEX idx_rpi_template ON repair_plan_items (template_id);
