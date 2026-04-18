-- ポイっとメモ本体
CREATE TABLE quick_memos (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id                     BIGINT UNSIGNED NOT NULL COMMENT 'FK -> users.id (ON DELETE CASCADE)',
    title                       VARCHAR(200)  NOT NULL,
    body                        TEXT          NULL,
    status                      VARCHAR(20)   NOT NULL DEFAULT 'UNSORTED' COMMENT 'UNSORTED / ARCHIVED / CONVERTED',
    reminder_uses_default       TINYINT(1)    NOT NULL DEFAULT 1,
    reminder_1_scheduled_at     DATETIME(6)   NULL,
    reminder_1_sent_at          DATETIME(6)   NULL,
    reminder_2_scheduled_at     DATETIME(6)   NULL,
    reminder_2_sent_at          DATETIME(6)   NULL,
    reminder_3_scheduled_at     DATETIME(6)   NULL,
    reminder_3_sent_at          DATETIME(6)   NULL,
    converted_to_todo_id        BIGINT UNSIGNED NULL COMMENT 'TODO 昇格先の todos.id（非FK）',
    converted_at                DATETIME(6)   NULL,
    user_timezone_at_creation   VARCHAR(50)   NOT NULL DEFAULT 'Asia/Tokyo',
    deleted_at                  DATETIME(6)   NULL COMMENT '論理削除（90日後に物理削除バッチが実行）',
    created_at                  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_quick_memos_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポイっとメモ';

CREATE INDEX idx_quick_memos_user_status ON quick_memos (user_id, status, deleted_at);
CREATE INDEX idx_quick_memos_reminder_1 ON quick_memos (reminder_1_scheduled_at, reminder_1_sent_at);
CREATE INDEX idx_quick_memos_reminder_2 ON quick_memos (reminder_2_scheduled_at, reminder_2_sent_at);
CREATE INDEX idx_quick_memos_reminder_3 ON quick_memos (reminder_3_scheduled_at, reminder_3_sent_at);
CREATE INDEX idx_quick_memos_deleted_at ON quick_memos (deleted_at);
