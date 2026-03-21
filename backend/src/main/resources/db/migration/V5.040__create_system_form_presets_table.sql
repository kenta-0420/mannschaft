-- F05.7 書類テンプレート・フォームビルダー: システムフォームプリセットテーブル
CREATE TABLE system_form_presets (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100)    NOT NULL,
    description     VARCHAR(500),
    category        VARCHAR(50),
    fields_json     JSON            NOT NULL,
    icon            VARCHAR(50),
    color           VARCHAR(7),
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_by      BIGINT          NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_system_form_presets_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_system_form_presets_category (category),
    INDEX idx_system_form_presets_is_active (is_active),
    INDEX idx_system_form_presets_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
