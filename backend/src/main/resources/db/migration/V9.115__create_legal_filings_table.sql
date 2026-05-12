-- F09.15 S1-A: legal_filings（不在者財産管理人 / 相続財産清算人 申立準備）
-- 設計書: docs/features/F09.15_resident_succession_support.md §5.8
--
-- 申立種別:
--   ABSENTEE_PROPERTY_MANAGER — 不在者財産管理人選任申立（家事事件手続法 145 条）
--   INHERITANCE_LIQUIDATOR    — 相続財産清算人選任申立（民法 952 条）
--
-- 区分所有法 8 条証拠 ZIP は evidence_package_s3_key + evidence_sha256 で保持。

CREATE TABLE legal_filings (
    id                       BINARY(16) NOT NULL,
    organization_id          BIGINT UNSIGNED NOT NULL,
    dwelling_unit_id         BIGINT UNSIGNED NOT NULL,
    resident_registry_id     BIGINT UNSIGNED NOT NULL,
    filing_type              VARCHAR(40) NOT NULL,
    template_pdf_s3_key      VARCHAR(500) NULL,
    evidence_package_s3_key  VARCHAR(500) NULL,
    evidence_built_at        DATETIME(6) NULL,
    evidence_sha256          CHAR(64) NULL,
    filed_externally_at      DATETIME(6) NULL,
    external_case_number     VARCHAR(100) NULL,
    note                     TEXT NULL,
    deleted_at               DATETIME(6) NULL,
    created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 申立種別 enum 制約（設計書 §4 申立種別 enum）
    CONSTRAINT chk_lf_filing_type CHECK (filing_type IN (
        'ABSENTEE_PROPERTY_MANAGER',
        'INHERITANCE_LIQUIDATOR'
    ))
);

CREATE INDEX idx_lf_org ON legal_filings (organization_id, deleted_at);
CREATE INDEX idx_lf_dwelling ON legal_filings (dwelling_unit_id);
CREATE INDEX idx_lf_resident_type ON legal_filings (resident_registry_id, filing_type);
