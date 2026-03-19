-- F02.3: TODO担当者テーブル
CREATE TABLE todo_assignees (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    todo_id     BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    assigned_by BIGINT UNSIGNED NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ta_todo_user (todo_id, user_id),
    INDEX idx_ta_user (user_id),
    CONSTRAINT fk_ta_todo FOREIGN KEY (todo_id) REFERENCES todos (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
