CREATE TABLE shared_file_versions (
    id             BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    file_id        BIGINT UNSIGNED       NOT NULL,
    version_number INT          NOT NULL,
    file_key       VARCHAR(500) NOT NULL,
    file_size      BIGINT UNSIGNED       NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    uploaded_by    BIGINT UNSIGNED NULL,
    comment        VARCHAR(500) NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_file_versions_file     FOREIGN KEY (file_id)     REFERENCES shared_files(id) ON DELETE CASCADE,
    CONSTRAINT fk_file_versions_uploaded FOREIGN KEY (uploaded_by) REFERENCES users(id)         ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
