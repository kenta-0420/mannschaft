-- F03.16 予定コメントスレッド: schedules にスレッド開閉フラグを追加
-- 設計書: docs/features/F03.16_schedule_comment_thread.md §3.2
-- Expand のみ・既存行を壊さない。既定 TRUE（開いている）。

ALTER TABLE schedules
  ADD COLUMN comments_enabled BOOLEAN NOT NULL DEFAULT TRUE
    COMMENT '予定コメントスレッドの開閉（FALSE = 新規投稿・返信・編集を拒否。既存コメントの閲覧は可）';
