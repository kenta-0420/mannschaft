ALTER TABLE shift_schedules
  ADD COLUMN is_reminder_sent_48h BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT '48時間前リマインド送信済みフラグ';
