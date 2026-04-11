-- TODO とタグの中間テーブル（F02.3 拡張）
CREATE TABLE todo_tag_links (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    todo_id    BIGINT      NOT NULL COMMENT 'FK -> todos.id (ON DELETE CASCADE)',
    tag_id     BIGINT      NOT NULL COMMENT 'FK -> tags.id (ON DELETE CASCADE)',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_todo_tag_links (todo_id, tag_id),
    CONSTRAINT fk_todo_tag_links_todo FOREIGN KEY (todo_id) REFERENCES todos (id) ON DELETE CASCADE,
    CONSTRAINT fk_todo_tag_links_tag  FOREIGN KEY (tag_id)  REFERENCES tags (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TODO-タグ中間テーブル（F02.3拡張）';

CREATE INDEX idx_todo_tag_links_tag ON todo_tag_links (tag_id);
