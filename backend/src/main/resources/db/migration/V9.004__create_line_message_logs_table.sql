-- =====================================================================
-- F09.4 LINE/SNS連携: LINEメッセージログテーブル
-- =====================================================================

CREATE TABLE line_message_logs (
    id                  BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    line_bot_config_id  BIGINT UNSIGNED  NOT NULL COMMENT 'FK → line_bot_configs',
    direction           VARCHAR(10)      NOT NULL COMMENT 'OUTBOUND / INBOUND',
    message_type        VARCHAR(20)      NOT NULL COMMENT 'TEXT / FLEX / TEMPLATE / IMAGE / WEBHOOK_EVENT',
    line_user_id        VARCHAR(50)      NULL     COMMENT '送受信対象',
    content_summary     VARCHAR(500)     NULL     COMMENT 'メッセージ概要',
    line_message_id     VARCHAR(50)      NULL     COMMENT 'LINE メッセージID',
    status              VARCHAR(20)      NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / DELIVERED / FAILED',
    error_detail        TEXT             NULL     COMMENT 'エラー詳細',
    created_at          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_lml_bot_created (line_bot_config_id, created_at DESC),
    INDEX idx_lml_user_created (line_user_id, created_at DESC),
    INDEX idx_lml_status (status, created_at),
    CONSTRAINT fk_lml_bot_config FOREIGN KEY (line_bot_config_id) REFERENCES line_bot_configs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LINEメッセージログ';
