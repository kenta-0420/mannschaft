CREATE TABLE kb_page_revisions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    kb_page_id BIGINT UNSIGNED NOT NULL,
    revision_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    body MEDIUMTEXT DEFAULT NULL,
    editor_id BIGINT UNSIGNED NOT NULL,
    change_summary VARCHAR(500) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_kbpr_page_revision (kb_page_id, revision_number),
    INDEX idx_kbpr_page (kb_page_id),
    CONSTRAINT fk_kbpr_kb_page FOREIGN KEY (kb_page_id) REFERENCES kb_pages(id) ON DELETE CASCADE,
    CONSTRAINT fk_kbpr_editor FOREIGN KEY (editor_id) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
