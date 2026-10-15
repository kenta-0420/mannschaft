-- =====================================================================
-- F09.4 LINE/SNS連携: ユーザーLINE連携テーブル
-- =====================================================================

CREATE TABLE user_line_connections (
    id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT UNSIGNED  NOT NULL COMMENT 'FK → users',
    line_user_id    VARCHAR(50)      NOT NULL COMMENT 'LINEユーザーID',
    display_name    VARCHAR(100)     NULL     COMMENT 'LINE表示名',
    picture_url     VARCHAR(500)     NULL     COMMENT 'プロフィール画像',
    status_message  VARCHAR(500)     NULL     COMMENT 'ステータスメッセージ',
    is_active       BOOLEAN          NOT NULL DEFAULT TRUE COMMENT '有効/無効',
    linked_at       DATETIME         NOT NULL COMMENT 'リンク日時',
    created_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_ulc_user_id (user_id),
    UNIQUE KEY uq_ulc_line_user_id (line_user_id),
    CONSTRAINT fk_ulc_user_id FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ユーザーLINE連携';
