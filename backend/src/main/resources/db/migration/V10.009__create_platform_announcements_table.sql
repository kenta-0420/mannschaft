-- F10.1 プラットフォームお知らせテーブル
CREATE TABLE platform_announcements (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title         VARCHAR(200)    NOT NULL,
    body          TEXT            NOT NULL,
    priority      VARCHAR(10)     NOT NULL DEFAULT 'NORMAL',
    target_scope  VARCHAR(20)     NOT NULL DEFAULT 'ALL',
    is_pinned     BOOLEAN         NOT NULL DEFAULT FALSE,
    published_at  DATETIME        NULL,
    expires_at    DATETIME        NULL,
    created_by    BIGINT UNSIGNED NOT NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at    DATETIME        NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pa_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_pa_published (published_at DESC, expires_at),
    INDEX idx_pa_priority (priority, published_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
