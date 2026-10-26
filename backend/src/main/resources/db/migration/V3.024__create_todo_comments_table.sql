-- F02.3: TODOコメントテーブル
CREATE TABLE todo_comments (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    todo_id    BIGINT UNSIGNED NOT NULL,
    user_id    BIGINT UNSIGNED NOT NULL,
    body       TEXT            NOT NULL,
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_tc_todo (todo_id, created_at),
    INDEX idx_tc_user (user_id),
    CONSTRAINT fk_tc_todo FOREIGN KEY (todo_id) REFERENCES todos (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
