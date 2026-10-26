CREATE TABLE kb_page_pins (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    kb_page_id BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(50) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    pinned_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_kbpp_page_scope (kb_page_id, scope_type, scope_id),
    INDEX idx_kbpp_scope (scope_type, scope_id, sort_order),
    CONSTRAINT fk_kbpp_kb_page FOREIGN KEY (kb_page_id) REFERENCES kb_pages(id) ON DELETE CASCADE,
    CONSTRAINT fk_kbpp_pinned_by FOREIGN KEY (pinned_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
