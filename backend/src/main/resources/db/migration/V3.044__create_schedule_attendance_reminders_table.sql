CREATE TABLE schedule_attendance_reminders (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT UNSIGNED NOT NULL,
    remind_at   DATETIME        NOT NULL,
    is_sent     BOOLEAN         NOT NULL DEFAULT FALSE,
    sent_at     DATETIME,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_sar_schedule (schedule_id),
    INDEX idx_sar_batch (is_sent, remind_at),

    CONSTRAINT fk_sar_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出欠リマインダー設定';
