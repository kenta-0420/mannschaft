-- F02.3.1 Phase 1a: SYSTEM 既定ステータスラベル3件を投入
-- 全ユーザーが共通で参照できる既定ラベル。is_system_default=TRUE で不変扱い。
--
-- ID は固定（1=未着手 / 2=着手中 / 3=完了）。
-- 既にこのマイグレーションが auto_increment で別 ID を割り当てて適用された開発環境では、
-- 改めて適用するために `flyway repair` で本マイグレーションのチェックサム再計算 + 既存
-- todo_status_labels の SYSTEM 行を削除（or 手動で id を 1〜3 に書き換え）してから本ファイル
-- を再実行する必要がある（手順は docs/operations 参照）。
INSERT INTO todo_status_labels
  (id, scope_type, scope_id, name, bucket, color, sort_order, is_system_default, created_at, updated_at)
VALUES
  (1, 'SYSTEM', NULL, '未着手', 'OPEN',         '#94a3b8', 0, TRUE, NOW(), NOW()),
  (2, 'SYSTEM', NULL, '着手中', 'IN_PROGRESS',  '#3b82f6', 1, TRUE, NOW(), NOW()),
  (3, 'SYSTEM', NULL, '完了',   'COMPLETED',    '#22c55e', 2, TRUE, NOW(), NOW());
