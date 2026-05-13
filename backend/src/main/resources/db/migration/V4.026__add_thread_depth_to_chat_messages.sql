-- F04.2 スレッド無制限ネスト対応: chat_messages に root_id・depth カラムを追加
ALTER TABLE chat_messages
  ADD COLUMN root_id BIGINT UNSIGNED NULL COMMENT 'スレッドルートメッセージID。NULL=自身がルート',
  ADD COLUMN depth INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'ネスト深度（0=トップレベル）';

ALTER TABLE chat_messages
  ADD CONSTRAINT fk_chat_messages_root FOREIGN KEY (root_id) REFERENCES chat_messages(id) ON DELETE SET NULL;

CREATE INDEX idx_chat_messages_root ON chat_messages (root_id, created_at ASC);
