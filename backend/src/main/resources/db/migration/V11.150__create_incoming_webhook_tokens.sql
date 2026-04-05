-- 受信Webhookトークンテーブル
CREATE TABLE incoming_webhook_tokens (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type          VARCHAR(50)  NOT NULL,
    scope_id            BIGINT UNSIGNED NOT NULL,
    token               VARCHAR(36)  NOT NULL UNIQUE COMMENT 'UUID v4',
    name                VARCHAR(100) NOT NULL,
    default_username    VARCHAR(100) NULL,
    default_avatar_url  VARCHAR(500) NULL,
    is_active           TINYINT(1)   NOT NULL DEFAULT 1,
    last_used_at        DATETIME     NULL,
    created_by          BIGINT UNSIGNED NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    deleted_at          DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_iwt_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_iwt_scope (scope_type, scope_id),
    INDEX idx_iwt_token (token)
);
