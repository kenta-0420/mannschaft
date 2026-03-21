CREATE TABLE shared_file_links (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    file_id         BIGINT       NOT NULL,
    token           CHAR(36)     NOT NULL,
    expires_at      DATETIME(6)  NULL,
    password_hash   VARCHAR(255) NULL,
    access_count    INT          NOT NULL DEFAULT 0,
    last_accessed_at DATETIME(6) NULL,
    created_by      BIGINT       NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_links_token (token),
    CONSTRAINT fk_file_links_file    FOREIGN KEY (file_id)    REFERENCES shared_files(id) ON DELETE CASCADE,
    CONSTRAINT fk_file_links_created FOREIGN KEY (created_by) REFERENCES users(id)         ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
