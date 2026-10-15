CREATE TABLE kb_image_uploads (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    kb_page_id BIGINT UNSIGNED DEFAULT NULL COMMENT 'NULL=未紐付け/アップロード中',
    uploader_id BIGINT UNSIGNED NOT NULL,
    s3_key VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_kbiu_s3_key (s3_key),
    INDEX idx_kbiu_page (kb_page_id),
    INDEX idx_kbiu_uploader (uploader_id),
    CONSTRAINT fk_kbiu_kb_page FOREIGN KEY (kb_page_id) REFERENCES kb_pages(id) ON DELETE SET NULL,
    CONSTRAINT fk_kbiu_uploader FOREIGN KEY (uploader_id) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
