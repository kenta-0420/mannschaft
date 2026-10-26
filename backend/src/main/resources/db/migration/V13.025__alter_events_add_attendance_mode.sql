ALTER TABLE events
  ADD COLUMN attendance_mode VARCHAR(20) NOT NULL DEFAULT 'REGISTRATION'
    COMMENT 'NONE=自由参加 / RSVP=出欠確認 / REGISTRATION=参加登録',
  ADD COLUMN pre_survey_id BIGINT UNSIGNED NULL
    COMMENT '事前アンケートFK (F05.4連携)',
  ADD CONSTRAINT fk_events_pre_survey
    FOREIGN KEY (pre_survey_id) REFERENCES surveys(id) ON DELETE SET NULL;
