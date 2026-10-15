-- =============================================
-- F07.1 サービス履歴: service_record_values テーブル
-- =============================================
CREATE TABLE service_record_values (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    service_record_id BIGINT UNSIGNED NOT NULL,
    field_id          BIGINT UNSIGNED NOT NULL,
    value             TEXT            NULL,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_srv_record FOREIGN KEY (service_record_id) REFERENCES service_records(id) ON DELETE CASCADE,
    CONSTRAINT fk_srv_field  FOREIGN KEY (field_id)          REFERENCES service_record_fields(id) ON DELETE RESTRICT,
    UNIQUE KEY uq_srv_record_field (service_record_id, field_id),
    INDEX idx_srv_field_value (field_id, value(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
