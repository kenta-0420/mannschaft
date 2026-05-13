-- F08.8 Phase 4: vendors テーブルに反社チェック状態カラムを追加する
-- repair_quote_cards の complianceCheckStatus スナップショット元として使用する

ALTER TABLE vendors
    ADD COLUMN compliance_check_status VARCHAR(20) NOT NULL DEFAULT 'UNCHECKED'
        COMMENT '反社チェック状態' AFTER updated_at,
    ADD COLUMN compliance_checked_at DATETIME NULL
        COMMENT '反社チェック実施日時' AFTER compliance_check_status;

ALTER TABLE vendors
    ADD CONSTRAINT chk_vendors_compliance_status
        CHECK (compliance_check_status IN ('UNCHECKED', 'PASSED', 'FAILED', 'EXPIRED'));

CREATE INDEX idx_vendors_compliance_status
    ON vendors (compliance_check_status, compliance_checked_at DESC);
