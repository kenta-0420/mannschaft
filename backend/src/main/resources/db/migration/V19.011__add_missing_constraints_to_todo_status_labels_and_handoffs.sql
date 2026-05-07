-- F02.3.1 Phase 2 追補: 設計書 §7 と V19.001/V19.004 の差分を埋める
-- 注: V19.007/008/010 が BIGINT（signed）で先行適用された環境向けに
--     型変更 ALTER を先行させてから FK/制約を追加する順序にしている。

-- ─────────────────────────────────────────────
-- Step1: todo_status_labels.id を参照している FK を一時的に DROP
-- ─────────────────────────────────────────────
-- todos → todo_status_labels(id) の FK が存在すると id の型変更が弾かれる
ALTER TABLE todos DROP FOREIGN KEY fk_todos_status_label;

-- ─────────────────────────────────────────────
-- Step2: BIGINT → BIGINT UNSIGNED 型変更
-- ─────────────────────────────────────────────
ALTER TABLE todo_status_labels
  MODIFY COLUMN id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  MODIFY COLUMN scope_id   BIGINT UNSIGNED NULL,
  MODIFY COLUMN created_by BIGINT UNSIGNED NULL;

ALTER TABLE todos
  MODIFY COLUMN status_label_id BIGINT UNSIGNED NULL;

-- ─────────────────────────────────────────────
-- Step3: 一時 DROP した FK を再追加
-- ─────────────────────────────────────────────
ALTER TABLE todos
  ADD CONSTRAINT fk_todos_status_label
  FOREIGN KEY (status_label_id) REFERENCES todo_status_labels(id) ON DELETE SET NULL;

-- ─────────────────────────────────────────────
-- todo_status_labels: 制約・インデックス追加
-- ─────────────────────────────────────────────

-- SYSTEM のとき scope_id IS NULL を強制（NULL 許容のセマンティクス両方を満たす論理式）
ALTER TABLE todo_status_labels
  ADD CONSTRAINT chk_tsl_scope_id_for_system
  CHECK (scope_type <> 'SYSTEM' OR scope_id IS NULL);

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
