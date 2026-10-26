-- カスタム項目値テーブル
CREATE TABLE chart_custom_values (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    chart_record_id BIGINT UNSIGNED NOT NULL,
    field_id BIGINT UNSIGNED NOT NULL,
    value TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ccv_chart FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE,
    CONSTRAINT fk_ccv_field FOREIGN KEY (field_id) REFERENCES chart_custom_fields(id) ON DELETE RESTRICT,
    CONSTRAINT uq_ccv_chart_field UNIQUE (chart_record_id, field_id)
);
