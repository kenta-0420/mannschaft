-- F03.12 ケア対象者見守り通知: event_rsvp_responses / event_registrations への通知・遅刻連絡列追加
ALTER TABLE event_rsvp_responses
  ADD COLUMN guardian_rsvp_notified_at      DATETIME NULL
    COMMENT 'RSVP確認保護者通知送信日時（冪等チェック用）',
  ADD COLUMN expected_arrival_minutes_late  INT NULL
    COMMENT '事前申告した遅刻分数（NULL=遅刻なし）',
  ADD COLUMN advance_absence_reason         ENUM('SICK','PERSONAL_REASON','OTHER') NULL
    COMMENT '事前欠席連絡時の理由';

ALTER TABLE event_registrations
  ADD COLUMN guardian_rsvp_notified_at      DATETIME NULL
    COMMENT 'RSVP確認保護者通知送信日時（冪等チェック用）';
