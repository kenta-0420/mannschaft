CREATE TABLE chat_channel_members (
    id           BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    channel_id   BIGINT UNSIGNED  NOT NULL,
    user_id      BIGINT UNSIGNED  NOT NULL,
    role         VARCHAR(20)      NOT NULL DEFAULT 'MEMBER',
    unread_count INT              NOT NULL DEFAULT 0,
    last_read_at DATETIME,
    is_muted     BOOLEAN          NOT NULL DEFAULT FALSE,
    is_pinned    BOOLEAN          NOT NULL DEFAULT FALSE,
    category     VARCHAR(50),
    joined_at    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_user (channel_id, user_id),
    CONSTRAINT fk_member_channel FOREIGN KEY (channel_id) REFERENCES chat_channels(id) ON DELETE CASCADE,
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='チャンネルメンバー';
