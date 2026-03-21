-- F05.7 書類テンプレート・フォームビルダー: フォームテンプレートフィールドテーブル
CREATE TABLE form_template_fields (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    template_id     BIGINT          NOT NULL,
    field_key       VARCHAR(50)     NOT NULL,
    field_label     VARCHAR(100)    NOT NULL,
    field_type      VARCHAR(20)     NOT NULL,
    is_required     BOOLEAN         NOT NULL DEFAULT FALSE,
    sort_order      INT             NOT NULL DEFAULT 0,
    auto_fill_key   VARCHAR(50),
    options_json    JSON,
    placeholder     VARCHAR(200),
    PRIMARY KEY (id),
    CONSTRAINT fk_form_template_fields_template FOREIGN KEY (template_id) REFERENCES form_templates(id) ON DELETE CASCADE,
    INDEX idx_form_template_fields_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
