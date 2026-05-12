-- F08.8 Phase 1: 相見積もりカンバン（1案件 = 1ボード）
-- repair_plan_items (同ドメイン) との関連は FK 許容、property_work_packages (F09.13) は ID 参照のみ。
CREATE TABLE repair_quote_kanbans (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    work_package_id BIGINT UNSIGNED NOT NULL, -- F09.13 property_work_packages.id（FKなし）
    repair_plan_item_id BINARY(16) NULL,
    title VARCHAR(200) NOT NULL,
    bid_deadline_at DATETIME NULL,
    visibility_to_member VARCHAR(20) NOT NULL DEFAULT 'ANONYMIZED',
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_by BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_rqk_repair_plan_item FOREIGN KEY (repair_plan_item_id)
        REFERENCES repair_plan_items (id) ON DELETE SET NULL,
    CONSTRAINT chk_rqk_scope_type CHECK (scope_type IN ('ORGANIZATION','TEAM')),
    CONSTRAINT chk_rqk_status CHECK (status IN ('OPEN','CLOSED','AWARDED','CANCELED')),
    CONSTRAINT chk_rqk_visibility CHECK (visibility_to_member IN ('HIDDEN','ANONYMIZED','FULL'))
);

CREATE INDEX idx_rqk_organization_id ON repair_quote_kanbans (organization_id);
CREATE INDEX idx_rqk_scope_status ON repair_quote_kanbans (scope_type, scope_id, status, deleted_at);
CREATE INDEX idx_rqk_work_package ON repair_quote_kanbans (work_package_id);
CREATE INDEX idx_rqk_repair_plan_item ON repair_quote_kanbans (repair_plan_item_id);
CREATE INDEX idx_rqk_deadline ON repair_quote_kanbans (scope_type, scope_id, bid_deadline_at);
