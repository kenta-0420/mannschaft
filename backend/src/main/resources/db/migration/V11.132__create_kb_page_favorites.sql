CREATE TABLE kb_page_favorites (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    kb_page_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_kbpf_page_user (kb_page_id, user_id),
    INDEX idx_kbpf_user (user_id),
    CONSTRAINT fk_kbpf_kb_page FOREIGN KEY (kb_page_id) REFERENCES kb_pages(id) ON DELETE CASCADE,
    CONSTRAINT fk_kbpf_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
