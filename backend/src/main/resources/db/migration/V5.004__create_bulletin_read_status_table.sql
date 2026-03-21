-- F05.1 掲示板: 既読ステータステーブル
CREATE TABLE bulletin_read_status (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    thread_id  BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    read_at    DATETIME(6) NOT NULL DEFAULT NOW(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_bulletin_read_status_thread FOREIGN KEY (thread_id) REFERENCES bulletin_threads(id) ON DELETE CASCADE,
    CONSTRAINT fk_bulletin_read_status_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_bulletin_read_status_thread_user (thread_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
