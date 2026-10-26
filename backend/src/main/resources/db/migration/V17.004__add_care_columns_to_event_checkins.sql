-- F03.12 ケア対象者見守り通知: event_checkins へのケア通知・点呼関連列追加
ALTER TABLE event_checkins
  ADD COLUMN checkout_at                   DATETIME NULL
    COMMENT 'チェックアウト日時（退場記録、任意）',
  ADD COLUMN guardian_checkin_notified_at  DATETIME NULL
    COMMENT 'チェックイン保護者通知送信日時（冪等チェック用）',
  ADD COLUMN guardian_checkout_notified_at DATETIME NULL
    COMMENT 'チェックアウト保護者通知送信日時（冪等チェック用）',
  ADD COLUMN roll_call_session_id          VARCHAR(36) NULL
    COMMENT '同一の点呼セッションで作成されたレコード群を識別するUUID',
  ADD COLUMN late_arrival_minutes          INT NULL
    COMMENT '点呼時に遅刻として記録した場合の遅刻分数（オプション）',
  ADD COLUMN absence_reason                ENUM('NOT_ARRIVED','SICK','PERSONAL_REASON','OTHER') NULL
    COMMENT '欠席記録の場合の理由（点呼で「欠席」とした場合のみ使用）';
