-- F02.7: マイルストーンゲート機能 - project_milestones 拡張
-- ゲート（関所）関連8カラム追加（progress_rate / is_locked / locked_by_milestone_id /
--   completion_mode / locked_at / unlocked_at / force_unlocked / version）
-- + FK（自己参照 ON DELETE SET NULL）+ 3 INDEX
ALTER TABLE project_milestones
  ADD COLUMN progress_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00 AFTER is_completed,
  ADD COLUMN is_locked BOOLEAN NOT NULL DEFAULT FALSE AFTER progress_rate,
  ADD COLUMN locked_by_milestone_id BIGINT UNSIGNED NULL AFTER is_locked,
  ADD COLUMN completion_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO' AFTER locked_by_milestone_id,
  ADD COLUMN locked_at DATETIME NULL AFTER completion_mode,
  ADD COLUMN unlocked_at DATETIME NULL AFTER locked_at,
  ADD COLUMN force_unlocked BOOLEAN NOT NULL DEFAULT FALSE AFTER unlocked_at,
  ADD COLUMN version BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER force_unlocked,
  ADD CONSTRAINT fk_pm_locked_by FOREIGN KEY (locked_by_milestone_id) REFERENCES project_milestones(id) ON DELETE SET NULL,
  ADD INDEX idx_pm_locked (project_id, is_locked),
  ADD INDEX idx_pm_completion_mode (project_id, completion_mode),
  ADD INDEX idx_pm_locked_by (locked_by_milestone_id);
