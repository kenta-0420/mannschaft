CREATE TABLE personal_schedule_reminders (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    schedule_id           BIGINT UNSIGNED NOT NULL,
    remind_before_minutes INT UNSIGNED    NOT NULL,
    notified              BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_psr_schedule_minutes (schedule_id, remind_before_minutes),
    INDEX idx_psr_batch (notified, schedule_id),
    CONSTRAINT fk_psr_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='個人スケジュールリマインダー';
