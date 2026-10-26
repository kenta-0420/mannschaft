-- Phase 1-A wave2: user_id クロスドメインFK撤廃（非auth系 9件）
--
-- 1000万ユーザー耐久DB再構築 Phase 1-A 第二波。
-- admin / onboarding / timetable / sync / social / todo ドメインから
-- user ドメイン (users テーブル) への越境 FOREIGN KEY を撤廃し、
-- 参照整合性はアプリケーション層（退会匿名化フロー）で保証する。
--
-- CLAUDE.md §1「クロスドメインFKは作らない」原則に従い撤廃。
-- 物理削除経路は存在しない（論理削除徹底・UserEntity.anonymize() 済）ため
-- CASCADE 撤廃による孤児行の発生は理論上ない。
--
-- 対象テーブル:
--   feedback_votes              (fk_fv_user              — ON DELETE CASCADE)
--   user_violations             (fk_uv_user              — ON DELETE CASCADE)
--   onboarding_progresses       (fk_op_user              — ON DELETE CASCADE)
--   data_exports                (fk_data_exports_user    — ON DELETE CASCADE)
--   personal_timetables         (fk_personal_timetable_user — ON DELETE CASCADE)
--   offline_sync_conflicts      (fk_offline_sync_conflicts_user — ON DELETE CASCADE)
--   user_blocks                 (fk_ub_blocker           — ON DELETE CASCADE)
--   user_blocks                 (fk_ub_blocked           — ON DELETE CASCADE)
--   todo_personal_memos         (fk_tpm_user             — ON DELETE CASCADE)

-- ===== admin ドメイン: feedback_votes =====
-- user_id は UNIQUE KEY uq_fv_feedback_user(feedback_id, user_id) の第2列 →
-- user_id 単独検索用 index を追加
ALTER TABLE feedback_votes DROP FOREIGN KEY fk_fv_user;
CREATE INDEX idx_fv_user_id ON feedback_votes (user_id);

-- ===== admin ドメイン: user_violations =====
-- user_id は INDEX idx_uv_user(user_id, is_active, created_at DESC) でカバー済 → 追加不要
ALTER TABLE user_violations DROP FOREIGN KEY fk_uv_user;

-- ===== onboarding ドメイン: onboarding_progresses =====
-- user_id は INDEX idx_op_user(user_id, status) でカバー済 → 追加不要
ALTER TABLE onboarding_progresses DROP FOREIGN KEY fk_op_user;

-- ===== data ドメイン: data_exports =====
-- INDEX idx_data_exports_user_id(user_id) 既存 → 追加不要
ALTER TABLE data_exports DROP FOREIGN KEY fk_data_exports_user;

-- ===== timetable ドメイン: personal_timetables =====
-- user_id は INDEX idx_pt_user_status(user_id, status) でカバー済 → 追加不要
ALTER TABLE personal_timetables DROP FOREIGN KEY fk_personal_timetable_user;

-- ===== sync ドメイン: offline_sync_conflicts =====
-- user_id は INDEX idx_osc_user_resolution(user_id, resolution, created_at DESC) でカバー済 → 追加不要
ALTER TABLE offline_sync_conflicts DROP FOREIGN KEY fk_offline_sync_conflicts_user;

-- ===== social ドメイン: user_blocks =====
-- blocker_id は UNIQUE KEY uq_user_blocks(blocker_id, blocked_id) でカバー済 → 追加不要
-- blocked_id は INDEX idx_user_blocks_blocked(blocked_id, blocker_id) でカバー済 → 追加不要
ALTER TABLE user_blocks DROP FOREIGN KEY fk_ub_blocker;
ALTER TABLE user_blocks DROP FOREIGN KEY fk_ub_blocked;

-- ===== todo ドメイン: todo_personal_memos =====
-- user_id は UNIQUE KEY uq_tpm_todo_user(todo_id, user_id) の第2列 →
-- user_id 単独検索用 index を追加
ALTER TABLE todo_personal_memos DROP FOREIGN KEY fk_tpm_user;
CREATE INDEX idx_tpm_user_id ON todo_personal_memos (user_id);
