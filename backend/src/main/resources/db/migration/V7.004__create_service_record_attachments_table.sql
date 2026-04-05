-- =============================================
-- F07.1 サービス履歴: service_record_attachments テーブル
-- =============================================
CREATE TABLE service_record_attachments (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    service_record_id BIGINT UNSIGNED NOT NULL,
    file_key          VARCHAR(500)    NOT NULL,
    file_name         VARCHAR(255)    NOT NULL,
    content_type      VARCHAR(100)    NOT NULL,
    file_size         INT UNSIGNED    NOT NULL,
    sort_order        INT             NOT NULL DEFAULT 0,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_sra_record FOREIGN KEY (service_record_id) REFERENCES service_records(id) ON DELETE CASCADE,
    INDEX idx_sra_record (service_record_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
