-- テンプレート×モジュール紐付けテーブル
CREATE TABLE template_modules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id BIGINT UNSIGNED NOT NULL,
    module_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_template_modules UNIQUE (template_id, module_id),
    CONSTRAINT fk_template_modules_template FOREIGN KEY (template_id) REFERENCES team_templates(id) ON DELETE CASCADE,
    CONSTRAINT fk_template_modules_module FOREIGN KEY (module_id) REFERENCES module_definitions(id) ON DELETE RESTRICT
);
