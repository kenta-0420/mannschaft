-- F04.2 アクティブスレッド数 denormalize: chat_channels に active_thread_count カラムを追加
ALTER TABLE chat_channels
  ADD COLUMN active_thread_count INT UNSIGNED NOT NULL DEFAULT 0
  COMMENT '返信が1件以上あるトップレベルメッセージ数（denormalize）';
