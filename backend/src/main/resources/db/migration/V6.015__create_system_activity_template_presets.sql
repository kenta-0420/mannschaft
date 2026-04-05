-- F06.4: プラットフォーム標準テンプレートテーブル
CREATE TABLE system_activity_template_presets (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    category                VARCHAR(30) NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    description             VARCHAR(500) NULL,
    icon                    VARCHAR(30) NULL,
    color                   VARCHAR(7) NULL,
    is_participant_required BOOLEAN NOT NULL DEFAULT TRUE,
    default_visibility      VARCHAR(20) NOT NULL DEFAULT 'MEMBERS_ONLY',
    fields_json             JSON NOT NULL,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at              DATETIME NULL,
    PRIMARY KEY (id),
    INDEX idx_satp_category (category, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
