CREATE TABLE chat_message_attachments (
    id           BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    message_id   BIGINT UNSIGNED  NOT NULL,
    file_key     VARCHAR(500)     NOT NULL,
    file_name    VARCHAR(255)     NOT NULL,
    file_size    BIGINT           NOT NULL,
    content_type VARCHAR(100)     NOT NULL,
    created_at   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_attachment_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='メッセージ添付ファイル';
