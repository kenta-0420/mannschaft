-- F02.3.1 Phase 1a: SYSTEM 既定ステータスラベル3件を投入
-- 全ユーザーが共通で参照できる既定ラベル。is_system_default=TRUE で不変扱い。
INSERT INTO todo_status_labels
  (scope_type, scope_id, name, bucket, color, sort_order, is_system_default, created_at, updated_at)
VALUES
  ('SYSTEM', NULL, '未着手', 'OPEN',         '#94a3b8', 0, TRUE, NOW(), NOW()),
  ('SYSTEM', NULL, '着手中', 'IN_PROGRESS',  '#3b82f6', 1, TRUE, NOW(), NOW()),
  ('SYSTEM', NULL, '完了',   'COMPLETED',    '#22c55e', 2, TRUE, NOW(), NOW());
