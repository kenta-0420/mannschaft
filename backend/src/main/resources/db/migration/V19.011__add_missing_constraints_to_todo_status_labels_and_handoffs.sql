-- F02.3.1 Phase 2 追補: 設計書 §7 と V19.001/V19.004 の差分を埋める
-- 既存テーブル（todos, users）の id 型に合わせて BIGINT で統一する（設計書の表記より既存DB整合を優先）。
--
-- 追加内容:
--   todo_status_labels:
--     - chk_tsl_scope_id_for_system: SYSTEM のとき scope_id IS NULL を強制
--     - fk_tsl_created_by:           users(id) 参照（ON DELETE SET NULL）
--     - idx_tsl_active:              deleted_at IS NULL の検索高速化
--   todo_handoffs:
--     - fk_handoff_from_user:        users(id) 参照
--     - fk_handoff_prev_label:       todo_status_labels(id) 参照（ON DELETE SET NULL）
--     - fk_handoff_new_label:        todo_status_labels(id) 参照（ON DELETE SET NULL）
--     - chk_handoff_prev_status:     status enum 値制約（OPEN/IN_PROGRESS/COMPLETED）
--     - chk_handoff_new_status:      同上
--     - idx_handoff_from_user:       操作者検索用インデックス

-- ─────────────────────────────────────────────
-- todo_status_labels: 制約・インデックス追加
-- ─────────────────────────────────────────────

-- SYSTEM のとき scope_id IS NULL を強制（NULL 許容のセマンティクス両方を満たす論理式）
ALTER TABLE todo_status_labels
  ADD CONSTRAINT chk_tsl_scope_id_for_system
  CHECK (scope_type <> 'SYSTEM' OR scope_id IS NULL);

-- created_by の型を BIGINT UNSIGNED に変更（users.id との整合）
ALTER TABLE todo_status_labels
  MODIFY COLUMN created_by BIGINT UNSIGNED NULL;

-- created_by → users(id) (ON DELETE SET NULL: ユーザー削除時もラベルは残す)
ALTER TABLE todo_status_labels
  ADD CONSTRAINT fk_tsl_created_by
  FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

-- 論理削除されていないラベルの検索高速化（同じスコープでの絞り込みと併用）
CREATE INDEX idx_tsl_active
  ON todo_status_labels (scope_type, scope_id, deleted_at);

-- ─────────────────────────────────────────────
-- todo_handoffs: 制約・インデックス追加
-- ─────────────────────────────────────────────

-- from_user_id → users(id) (操作者削除時もログは残るため SET NULL ではなく NO ACTION)
-- 物理的な user 削除は通常想定されないが、テスト環境で user を消した瞬間に handoff が
-- 整合性違反で削除されないよう、ON DELETE は明示しない（= MySQL のデフォルト RESTRICT）。
ALTER TABLE todo_handoffs
  ADD CONSTRAINT fk_handoff_from_user
  FOREIGN KEY (from_user_id) REFERENCES users(id);

-- previous_status_label_id → todo_status_labels(id) (ON DELETE SET NULL)
-- ラベル削除時は履歴は snapshot 名で残す（FK は NULL になる）
ALTER TABLE todo_handoffs
  ADD CONSTRAINT fk_handoff_prev_label
  FOREIGN KEY (previous_status_label_id) REFERENCES todo_status_labels(id) ON DELETE SET NULL;

-- new_status_label_id → todo_status_labels(id) (ON DELETE SET NULL)
ALTER TABLE todo_handoffs
  ADD CONSTRAINT fk_handoff_new_label
  FOREIGN KEY (new_status_label_id) REFERENCES todo_status_labels(id) ON DELETE SET NULL;

-- status enum 値制約（OPEN/IN_PROGRESS/COMPLETED の3バケットのみ）
ALTER TABLE todo_handoffs
  ADD CONSTRAINT chk_handoff_prev_status
  CHECK (previous_status IN ('OPEN','IN_PROGRESS','COMPLETED'));

ALTER TABLE todo_handoffs
  ADD CONSTRAINT chk_handoff_new_status
  CHECK (new_status IN ('OPEN','IN_PROGRESS','COMPLETED'));

-- 操作者ベースの検索（マイ履歴・監査）高速化
CREATE INDEX idx_handoff_from_user
  ON todo_handoffs (from_user_id, created_at DESC);
