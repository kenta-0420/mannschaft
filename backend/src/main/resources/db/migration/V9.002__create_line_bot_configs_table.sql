-- =====================================================================
-- F09.4 LINE/SNS連携: LINE BOT設定テーブル
-- =====================================================================

CREATE TABLE line_bot_configs (
    id                        BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    scope_type                VARCHAR(20)      NOT NULL COMMENT 'TEAM / ORGANIZATION',
    scope_id                  BIGINT UNSIGNED  NOT NULL COMMENT 'teams.id / organizations.id',
    channel_id                VARCHAR(100)     NOT NULL COMMENT 'LINE チャンネルID',
    channel_secret_enc        VARBINARY(512)   NOT NULL COMMENT 'AES-256-GCM暗号化',
    channel_access_token_enc  VARBINARY(1024)  NOT NULL COMMENT 'AES-256-GCM暗号化',
    encryption_key_version    INT UNSIGNED     NOT NULL DEFAULT 1 COMMENT '暗号化キーバージョン',
    webhook_secret            VARCHAR(64)      NOT NULL COMMENT 'Webhook検証シークレット',
    bot_user_id               VARCHAR(50)      NULL     COMMENT 'LINE BOTユーザーID',
    is_active                 BOOLEAN          NOT NULL DEFAULT TRUE  COMMENT 'BOT有効/無効',
    notification_enabled      BOOLEAN          NOT NULL DEFAULT TRUE  COMMENT '通知有効/無効',
    configured_by             BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users',
    created_at                DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                DATETIME         NULL     COMMENT '論理削除',

    INDEX idx_lbc_scope (scope_type, scope_id),
    INDEX idx_lbc_channel (channel_id),
    CONSTRAINT fk_lbc_configured_by FOREIGN KEY (configured_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LINE BOT設定';
