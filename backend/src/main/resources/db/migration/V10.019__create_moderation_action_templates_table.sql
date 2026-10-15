-- モデレーション対応テンプレートテーブル
CREATE TABLE moderation_action_templates (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name           VARCHAR(100)    NOT NULL,
    action_type    VARCHAR(20)     NOT NULL,
    reason         VARCHAR(30)     NULL,
    template_text  TEXT            NOT NULL,
    language       VARCHAR(10)     NOT NULL DEFAULT 'ja',
    is_default     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_by     BIGINT UNSIGNED NOT NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at     DATETIME        NULL,
    PRIMARY KEY (id),
    INDEX idx_mat_type_reason (action_type, reason, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
