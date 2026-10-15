-- F04.8: 個人連絡先招待トークンテーブル
CREATE TABLE contact_invite_tokens (
    id          BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED  NOT NULL COMMENT 'トークン発行者',
    token       CHAR(36)         NOT NULL COMMENT 'UUID v4',
    label       VARCHAR(50)      NULL     COMMENT '管理用ラベル',
    max_uses    INT              NULL     COMMENT 'NULL=無制限',
    used_count  INT              NOT NULL DEFAULT 0,
    expires_at  DATETIME         NULL     COMMENT 'NULL=無期限',
    revoked_at  DATETIME         NULL,
    created_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_cit_token (token),
    INDEX idx_cit_user (user_id, revoked_at, expires_at),
    CONSTRAINT fk_cit_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='個人連絡先招待トークン';
