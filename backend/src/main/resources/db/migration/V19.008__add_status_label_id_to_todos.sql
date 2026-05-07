-- F02.3.1 Phase 1a: todos に status_label_id を追加
-- ラベル削除時は ON DELETE SET NULL とし、フロント側でバケットからシステム既定にフォールバック表示する。
ALTER TABLE todos ADD COLUMN status_label_id BIGINT NULL AFTER status;
ALTER TABLE todos ADD CONSTRAINT fk_todos_status_label
  FOREIGN KEY (status_label_id) REFERENCES todo_status_labels(id) ON DELETE SET NULL;
CREATE INDEX idx_todos_status_label_id ON todos(status_label_id);
