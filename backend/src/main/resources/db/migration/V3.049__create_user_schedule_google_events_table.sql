CREATE TABLE user_schedule_google_events (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id         BIGINT UNSIGNED NOT NULL,
    schedule_id     BIGINT UNSIGNED NOT NULL,
    google_event_id VARCHAR(255)    NOT NULL,
    last_synced_at  DATETIME        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_usge_user_schedule (user_id, schedule_id),
    INDEX idx_usge_schedule (schedule_id),
    CONSTRAINT fk_usge_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_usge_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='スケジュール↔Googleイベントマッピング';
