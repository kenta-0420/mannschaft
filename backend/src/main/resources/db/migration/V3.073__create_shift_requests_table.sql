CREATE TABLE shift_requests (
    id              BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    schedule_id     BIGINT UNSIGNED  NOT NULL,
    user_id         BIGINT UNSIGNED  NOT NULL,
    slot_id         BIGINT UNSIGNED,
    slot_date       DATE             NOT NULL,
    preference      VARCHAR(20)      NOT NULL,
    note            VARCHAR(200),
    submitted_at    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_sr_schedule_user (schedule_id, user_id),
    INDEX idx_sr_schedule_date (schedule_id, slot_date),

    CONSTRAINT fk_sr_schedule FOREIGN KEY (schedule_id) REFERENCES shift_schedules (id) ON DELETE CASCADE,
    CONSTRAINT fk_sr_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_sr_slot FOREIGN KEY (slot_id) REFERENCES shift_slots (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='シフト希望提出';
