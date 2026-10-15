-- F09.1 住民台帳: 居住者書類テーブル
CREATE TABLE resident_documents (
    id                  BIGINT UNSIGNED     NOT NULL AUTO_INCREMENT,
    resident_id         BIGINT UNSIGNED     NOT NULL,
    document_type       VARCHAR(30)         NOT NULL,
    file_name           VARCHAR(255)        NOT NULL,
    s3_key              VARCHAR(500)        NOT NULL,
    file_size           INT                 NOT NULL,
    content_type        VARCHAR(100)        NOT NULL,
    uploaded_by         BIGINT UNSIGNED     NOT NULL,
    created_at          DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_rd_resident
        FOREIGN KEY (resident_id) REFERENCES resident_registry (id) ON DELETE CASCADE,
    CONSTRAINT fk_rd_uploaded_by
        FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_rd_resident (resident_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='居住者書類';
