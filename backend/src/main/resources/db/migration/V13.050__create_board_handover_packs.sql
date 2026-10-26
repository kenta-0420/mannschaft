-- F08.8 Phase 1: 任期終了時の申し送り PDF メタデータ
CREATE TABLE board_handover_packs (
    id BINARY(16) NOT NULL,
    organization_id BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    term_year SMALLINT UNSIGNED NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    pdf_r2_key VARCHAR(500) NULL,
    pdf_size INT UNSIGNED NULL,
    pdf_sha256 CHAR(64) NULL,
    pii_level VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    viewer_watermark_template VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATING',
    password_separately_sent TINYINT(1) NOT NULL DEFAULT 0,
    generated_by BIGINT UNSIGNED NOT NULL,
    generated_at DATETIME NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_bhp_scope_type CHECK (scope_type IN ('ORGANIZATION','TEAM')),
    CONSTRAINT chk_bhp_status CHECK (status IN ('GENERATING','COMPLETED','FAILED')),
    CONSTRAINT chk_bhp_pii_level CHECK (pii_level IN ('STANDARD','ANONYMIZED'))
);

CREATE INDEX idx_bhp_organization_id ON board_handover_packs (organization_id);
CREATE INDEX idx_bhp_scope_year ON board_handover_packs (scope_type, scope_id, term_year);
CREATE INDEX idx_bhp_status ON board_handover_packs (status, created_at DESC);
