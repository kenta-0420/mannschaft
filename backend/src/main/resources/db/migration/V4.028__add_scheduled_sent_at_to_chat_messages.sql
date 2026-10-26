-- F04.2: 予約送信バッチ対応
-- scheduled_sent_at: バッチが予約メッセージを配信した日時。NULL = 未配信。
ALTER TABLE chat_messages ADD COLUMN scheduled_sent_at DATETIME NULL COMMENT '予約送信実行日時';

-- (scheduled_at, scheduled_sent_at) の複合インデックス。
-- バッチが「scheduled_at <= NOW() AND scheduled_sent_at IS NULL AND deleted_at IS NULL」で絞り込む際に使用。
CREATE INDEX idx_chat_messages_scheduled ON chat_messages(scheduled_at, scheduled_sent_at);
