CREATE TABLE chat_message_bookmarks (
    id         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    message_id BIGINT UNSIGNED  NOT NULL,
    user_id    BIGINT UNSIGNED  NOT NULL,
    note       VARCHAR(200),
    created_at DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_bookmark_user_message (user_id, message_id),
    CONSTRAINT fk_bookmark_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE,
    CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='メッセージブックマーク';
