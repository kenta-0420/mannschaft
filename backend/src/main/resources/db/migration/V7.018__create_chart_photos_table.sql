-- カルテ写真テーブル
CREATE TABLE chart_photos (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    chart_record_id BIGINT UNSIGNED NOT NULL,
    photo_type VARCHAR(20) NOT NULL,
    s3_key VARCHAR(500) NOT NULL,
    original_filename VARCHAR(300) NOT NULL,
    file_size_bytes INT UNSIGNED NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    note VARCHAR(300) NULL,
    is_shared_to_customer TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_cp_chart FOREIGN KEY (chart_record_id) REFERENCES chart_records(id) ON DELETE CASCADE
);
CREATE INDEX idx_cp_chart ON chart_photos(chart_record_id);
CREATE INDEX idx_cp_chart_type ON chart_photos(chart_record_id, photo_type);
