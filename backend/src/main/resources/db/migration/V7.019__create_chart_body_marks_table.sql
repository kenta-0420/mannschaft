-- 身体チャートマーク情報テーブル
CREATE TABLE chart_body_marks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    chart_record_id BIGINT UNSIGNED NOT NULL,
    body_part VARCHAR(20) NOT NULL,
    x_position DECIMAL(5,2) NOT NULL,
    y_position DECIMAL(5,2) NOT NULL,
    mark_type VARCHAR(20) NOT NULL,
    severity TINYINT UNSIGNED NOT NULL,
    note VARCHAR(300) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_cbm_chart FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE,
    CONSTRAINT chk_cbm_severity CHECK (severity BETWEEN 1 AND 5)
);
CREATE INDEX idx_cbm_chart ON chart_body_marks(chart_record_id);
