-- F05.2 回覧板: circulation_comments テーブル
CREATE TABLE circulation_comments (
    id          BIGINT UNSIGNED        NOT NULL AUTO_INCREMENT,
    document_id BIGINT UNSIGNED        NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    body        VARCHAR(1000) NOT NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME      NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_circulation_comments_document FOREIGN KEY (document_id) REFERENCES circulation_documents(id) ON DELETE CASCADE,
    CONSTRAINT fk_circulation_comments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
