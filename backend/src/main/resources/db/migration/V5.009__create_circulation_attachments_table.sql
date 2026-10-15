-- F05.2 回覧板: circulation_attachments テーブル
CREATE TABLE circulation_attachments (
    id                BIGINT UNSIGNED       NOT NULL AUTO_INCREMENT,
    document_id       BIGINT UNSIGNED       NOT NULL,
    file_key          VARCHAR(500) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_size         BIGINT UNSIGNED       NOT NULL,
    mime_type         VARCHAR(100) NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_circulation_attachments_document FOREIGN KEY (document_id) REFERENCES circulation_documents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
