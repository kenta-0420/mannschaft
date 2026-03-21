-- F05.1 掲示板: 返信テーブル
CREATE TABLE bulletin_replies (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    thread_id   BIGINT      NOT NULL,
    parent_id   BIGINT,
    author_id   BIGINT,
    body        TEXT        NOT NULL,
    is_edited   BOOLEAN     NOT NULL DEFAULT FALSE,
    reply_count INT         NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    deleted_at  DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_bulletin_replies_thread FOREIGN KEY (thread_id) REFERENCES bulletin_threads(id) ON DELETE CASCADE,
    CONSTRAINT fk_bulletin_replies_parent FOREIGN KEY (parent_id) REFERENCES bulletin_replies(id) ON DELETE CASCADE,
    CONSTRAINT fk_bulletin_replies_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
