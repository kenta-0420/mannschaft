-- F03.12 ケア対象者見守り通知: events への解散通知・リマインド列追加
ALTER TABLE events
  ADD COLUMN dismissal_notification_sent_at DATETIME NULL
    COMMENT '解散通知送信日時（NULL=未送信）',
  ADD COLUMN dismissal_notified_by          BIGINT NULL
    COMMENT '解散通知を送信した主催者users.id',
  ADD COLUMN organizer_reminder_sent_count  TINYINT NOT NULL DEFAULT 0
    COMMENT '主催者へ送信したリマインド回数（最大3回）',
  ADD COLUMN last_organizer_reminder_at     DATETIME NULL
    COMMENT '最終主催者リマインド送信日時';
