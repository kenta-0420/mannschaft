-- =============================================
-- F07.1 サービス履歴: service_record_template_values テーブル
-- =============================================
CREATE TABLE service_record_template_values (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id   BIGINT UNSIGNED NOT NULL,
    field_id      BIGINT UNSIGNED NOT NULL,
    default_value TEXT            NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_srtv_template FOREIGN KEY (template_id) REFERENCES service_record_templates(id) ON DELETE CASCADE,
    CONSTRAINT fk_srtv_field    FOREIGN KEY (field_id)     REFERENCES service_record_fields(id)    ON DELETE RESTRICT,
    UNIQUE KEY uq_srtv_template_field (template_id, field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
