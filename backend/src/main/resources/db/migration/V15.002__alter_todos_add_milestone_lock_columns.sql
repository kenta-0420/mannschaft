-- F02.7: マイルストーンゲート機能 - todos 拡張
-- マイルストーンロック関連2カラム追加（milestone_locked / position）+ 2 INDEX
ALTER TABLE todos
  ADD COLUMN milestone_locked BOOLEAN NOT NULL DEFAULT FALSE AFTER milestone_id,
  ADD COLUMN position SMALLINT UNSIGNED NOT NULL DEFAULT 0 AFTER milestone_locked,
  ADD INDEX idx_todo_locked (project_id, milestone_id, milestone_locked),
  ADD INDEX idx_todo_position (milestone_id, position);
