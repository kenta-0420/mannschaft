-- 問診票・同意書テーブル
CREATE TABLE chart_intake_forms (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    chart_record_id BIGINT UNSIGNED NOT NULL,
    form_type VARCHAR(20) NOT NULL,
    content JSON NOT NULL,
    electronic_seal_id BIGINT UNSIGNED NULL,
    signed_at DATETIME NULL,
    is_initial TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_cif_chart FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE,
    CONSTRAINT fk_cif_seal FOREIGN KEY (electronic_seal_id) REFERENCES electronic_seals(id)
);
CREATE INDEX idx_cif_chart ON chart_intake_forms(chart_record_id);
CREATE INDEX idx_cif_type ON chart_intake_forms(chart_record_id, form_type);
