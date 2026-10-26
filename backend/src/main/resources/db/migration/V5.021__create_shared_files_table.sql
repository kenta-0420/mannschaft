CREATE TABLE shared_files (
    id              BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    folder_id       BIGINT UNSIGNED       NOT NULL,
    name            VARCHAR(255) NOT NULL,
    file_key        VARCHAR(500) NOT NULL,
    file_size       BIGINT UNSIGNED       NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    description     VARCHAR(500) NULL,
    created_by      BIGINT UNSIGNED NULL,
    current_version INT          NOT NULL DEFAULT 1,
    version         BIGINT UNSIGNED       NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    deleted_at      DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_shared_files_folder  FOREIGN KEY (folder_id)  REFERENCES shared_folders(id) ON DELETE CASCADE,
    CONSTRAINT fk_shared_files_created FOREIGN KEY (created_by) REFERENCES users(id)           ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
