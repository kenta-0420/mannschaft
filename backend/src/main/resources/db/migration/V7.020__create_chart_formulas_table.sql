-- カラー・薬剤レシピテーブル
CREATE TABLE chart_formulas (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    chart_record_id BIGINT UNSIGNED NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    ratio VARCHAR(100) NULL,
    processing_time_minutes SMALLINT UNSIGNED NULL,
    temperature VARCHAR(50) NULL,
    patch_test_date DATE NULL,
    patch_test_result VARCHAR(20) NULL,
    note VARCHAR(500) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_cf_chart FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE
);
CREATE INDEX idx_cf_chart ON chart_formulas(chart_record_id);
