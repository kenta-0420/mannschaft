-- F09.16 S1-B: residence-status ドメイン
-- monitoring_committee_visits（訪問記録）
-- すべての他ドメイン参照（committees / users / succession_covenants / dwelling_units / resident_registry）は
-- INDEX のみで FK なし（CLAUDE.md DB設計原則 1 準拠）
-- consideration_memo_encrypted は @Convert(EncryptedStringConverter) で AES-256-GCM 暗号化
CREATE TABLE monitoring_committee_visits (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    dwelling_unit_id BIGINT UNSIGNED NOT NULL,
    resident_registry_id BIGINT UNSIGNED NOT NULL,
    subject_user_id BIGINT UNSIGNED NOT NULL,
    committee_id BIGINT UNSIGNED NOT NULL,
    visitor_user_id BIGINT UNSIGNED NOT NULL,
    visited_at DATETIME(6) NOT NULL,
    contact_result VARCHAR(20) NOT NULL,
    consideration_memo_encrypted TEXT NULL,
    next_visit_recommended_at DATE NULL,
    consent_covenant_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_mcv_committee (committee_id, visited_at DESC),
    INDEX idx_mcv_subject (subject_user_id, visited_at DESC),
    INDEX idx_mcv_resident_result (resident_registry_id, contact_result, visited_at DESC),
    INDEX idx_mcv_org (organization_id, deleted_at),
    INDEX idx_mcv_dwelling (dwelling_unit_id),
    INDEX idx_mcv_visitor (visitor_user_id),
    INDEX idx_mcv_covenant (consent_covenant_id),
    CONSTRAINT chk_mcv_contact_result CHECK (contact_result IN (
        'MET','NO_RESPONSE','MAILBOX_ABNORMAL','METER_ABNORMAL','NEIGHBOR_INFO','REFUSED','OTHER'
    ))
);
