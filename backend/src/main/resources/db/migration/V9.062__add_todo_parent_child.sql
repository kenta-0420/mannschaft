-- TODO親子階層サポート: parent_id / depth カラム追加
-- ON DELETE SET NULL: 親の物理削除時も子データを保護（運用上は論理削除のみ）
ALTER TABLE todos
  ADD COLUMN parent_id BIGINT UNSIGNED NULL AFTER milestone_id,
  ADD COLUMN depth     TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER parent_id,
  ADD CONSTRAINT fk_todo_parent
    FOREIGN KEY (parent_id) REFERENCES todos(id)
    ON DELETE SET NULL;

CREATE INDEX idx_todo_parent ON todos(parent_id, deleted_at, sort_order);
CREATE INDEX idx_todo_depth  ON todos(parent_id, depth);
