-- F02.3.1 Phase 2: TODO キャッチボール（引き渡し）履歴テーブル
--
-- TODO の引き渡し（assignee 置換 + status/label 変更 + メッセージ）を1行
-- に記録する履歴テーブル。タイムライン UI で「いつ・誰から・誰へ・どんな
-- 状態に・どんなメッセージで」渡されたかを表示するソース。
--
-- ラベル参照 (previous/new_status_label_id) は ON DELETE SET NULL ではなく
-- そのまま BIGINT 値を保持し、削除されたラベルでも履歴が壊れないように
-- スナップショット名 (previous/new_status_label_name) を併存させる。

CREATE TABLE todo_handoffs (
  id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
  todo_id                     BIGINT NOT NULL,
  from_user_id                BIGINT NOT NULL,
  from_assignee_user_ids      JSON NOT NULL,
  to_assignee_user_ids        JSON NOT NULL,
  previous_status             VARCHAR(20) NOT NULL,
  previous_status_label_id    BIGINT NULL,
  previous_status_label_name  VARCHAR(50) NULL,
  new_status                  VARCHAR(20) NOT NULL,
  new_status_label_id         BIGINT NULL,
  new_status_label_name       VARCHAR(50) NULL,
  message                     VARCHAR(500) NULL,
  created_at                  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_handoff_todo FOREIGN KEY (todo_id) REFERENCES todos(id) ON DELETE CASCADE,
  INDEX idx_handoff_todo (todo_id, created_at DESC)
);
