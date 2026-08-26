ALTER TABLE user_schedule_google_events
    ADD COLUMN sync_direction ENUM('PUSH_ONLY', 'BIDIRECTIONAL') NOT NULL DEFAULT 'PUSH_ONLY',
    ADD COLUMN google_etag    VARCHAR(255) NULL,
    ADD INDEX  idx_usge_google_event_id (google_event_id);
