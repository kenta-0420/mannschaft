CREATE TABLE chat_message_reactions (
    id         BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    message_id BIGINT UNSIGNED  NOT NULL,
    user_id    BIGINT UNSIGNED  NOT NULL,
    emoji      VARCHAR(50)      NOT NULL,
    created_at DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_reaction_user_emoji (message_id, user_id, emoji),
    CONSTRAINT fk_reaction_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE,
    CONSTRAINT fk_reaction_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='メッセージリアクション';
