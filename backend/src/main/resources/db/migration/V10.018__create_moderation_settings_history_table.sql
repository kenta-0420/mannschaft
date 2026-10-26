-- モデレーション設定変更履歴テーブル
CREATE TABLE moderation_settings_history (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    setting_key  VARCHAR(100)    NOT NULL,
    old_value    VARCHAR(500)    NOT NULL,
    new_value    VARCHAR(500)    NOT NULL,
    changed_by   BIGINT UNSIGNED NOT NULL,
    changed_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_msh_user FOREIGN KEY (changed_by) REFERENCES users (id),
    INDEX idx_msh_key (setting_key, changed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
