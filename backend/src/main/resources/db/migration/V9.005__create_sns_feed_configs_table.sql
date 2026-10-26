-- =====================================================================
-- F09.4 LINE/SNS連携: SNSフィード設定テーブル
-- =====================================================================

CREATE TABLE sns_feed_configs (
    id                      BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    scope_type              VARCHAR(20)      NOT NULL COMMENT 'TEAM / ORGANIZATION',
    scope_id                BIGINT UNSIGNED  NOT NULL COMMENT 'teams.id / organizations.id',
    provider                VARCHAR(20)      NOT NULL COMMENT 'INSTAGRAM（将来: X）',
    account_username        VARCHAR(100)     NOT NULL COMMENT 'アカウント名',
    access_token_enc        VARBINARY(1024)  NULL     COMMENT 'アクセストークン（暗号化）',
    encryption_key_version  INT UNSIGNED     NOT NULL DEFAULT 1 COMMENT '暗号化キーバージョン',
    display_count           SMALLINT UNSIGNED NOT NULL DEFAULT 6 COMMENT '表示件数',
    is_active               BOOLEAN          NOT NULL DEFAULT TRUE,
    configured_by           BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users',
    created_at              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at              DATETIME         NULL     COMMENT '論理削除',

    UNIQUE KEY uq_sfc_scope_provider (scope_type, scope_id, provider, deleted_at),
    INDEX idx_sfc_scope (scope_type, scope_id, is_active),
    CONSTRAINT fk_sfc_configured_by FOREIGN KEY (configured_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SNSフィード設定';
