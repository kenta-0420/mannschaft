-- モデレーション設定テーブル
CREATE TABLE moderation_settings (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    setting_key    VARCHAR(100)    NOT NULL,
    setting_value  VARCHAR(500)    NOT NULL,
    description    VARCHAR(500)    NULL,
    updated_by     BIGINT UNSIGNED NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ms_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
