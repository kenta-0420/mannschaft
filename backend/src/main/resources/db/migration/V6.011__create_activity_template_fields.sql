-- F06.4: テンプレートフィールド定義テーブル
CREATE TABLE activity_template_fields (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id     BIGINT UNSIGNED NOT NULL,
    field_key       VARCHAR(50) NOT NULL,
    field_label     VARCHAR(100) NOT NULL,
    field_type      VARCHAR(20) NOT NULL,
    is_required     BOOLEAN NOT NULL DEFAULT FALSE,
    options_json    JSON NULL,
    placeholder     VARCHAR(200) NULL,
    unit            VARCHAR(20) NULL,
    is_aggregatable BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order      INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_atf_template (template_id, sort_order),
    UNIQUE KEY uq_atf_key (template_id, field_key),
    CONSTRAINT fk_atf_template FOREIGN KEY (template_id) REFERENCES activity_templates (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
