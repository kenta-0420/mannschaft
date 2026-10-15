-- F08.8 Phase 1: 業者見積カード（1ボード内で複数業者の見積行を保持）
-- repair_quote_kanbans (同ドメイン) とは FK + CASCADE 許容、vendors (F09.13) は ID 参照のみ。
CREATE TABLE repair_quote_cards (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    kanban_id BINARY(16) NOT NULL,
    vendor_id BIGINT UNSIGNED NOT NULL, -- F09.13 vendors.id（FKなし）
    vendor_name_snapshot VARCHAR(150) NOT NULL,
    stage VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    amount BIGINT UNSIGNED NULL,
    breakdown_json JSON NULL,
    bid_token_hash CHAR(64) NULL,
    is_visible_after DATETIME NULL,
    compliance_check_status VARCHAR(20) NOT NULL DEFAULT 'UNCHECKED',
    compliance_checked_at DATETIME NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_rqc_kanban FOREIGN KEY (kanban_id)
        REFERENCES repair_quote_kanbans (id) ON DELETE CASCADE,
    CONSTRAINT chk_rqc_stage CHECK (stage IN ('REQUESTED','RECEIVED','UNDER_REVIEW','SHORTLISTED','SELECTED','REJECTED')),
    CONSTRAINT chk_rqc_compliance CHECK (compliance_check_status IN ('UNCHECKED','PASSED','FAILED','EXPIRED')),
    CONSTRAINT chk_rqc_amount CHECK (amount IS NULL OR amount >= 0)
);

CREATE INDEX idx_rqc_organization_id ON repair_quote_cards (organization_id);
CREATE INDEX idx_rqc_kanban_stage ON repair_quote_cards (kanban_id, stage, display_order);
CREATE INDEX idx_rqc_vendor ON repair_quote_cards (vendor_id);
CREATE INDEX idx_rqc_compliance ON repair_quote_cards (compliance_check_status, compliance_checked_at);
