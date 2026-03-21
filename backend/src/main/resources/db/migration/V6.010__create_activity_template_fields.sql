-- F06.4: テンプレートフィールド定義テーブル
CREATE TABLE activity_template_fields (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id   BIGINT UNSIGNED NOT NULL,
    scope         VARCHAR(15) NOT NULL DEFAULT 'ACTIVITY',
    field_name    VARCHAR(100) NOT NULL,
    field_type    VARCHAR(20) NOT NULL,
    options       JSON NULL,
    unit          VARCHAR(20) NULL,
    is_required   BOOLEAN NOT NULL DEFAULT FALSE,
    default_value VARCHAR(500) NULL,
    sort_order    INT NOT NULL DEFAULT 0,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_atf_template_scope (template_id, scope, sort_order),
    CONSTRAINT fk_atf_template FOREIGN KEY (template_id) REFERENCES activity_templates (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
