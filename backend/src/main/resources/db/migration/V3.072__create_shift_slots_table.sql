CREATE TABLE shift_slots (
    id               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    schedule_id      BIGINT UNSIGNED  NOT NULL,
    slot_date        DATE             NOT NULL,
    start_time       TIME             NOT NULL,
    end_time         TIME             NOT NULL,
    position_id      BIGINT UNSIGNED,
    required_count   TINYINT UNSIGNED NOT NULL DEFAULT 1,
    assigned_user_ids JSON,
    note             VARCHAR(200),
    version          BIGINT           NOT NULL DEFAULT 0,
    created_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_sslot_schedule_date (schedule_id, slot_date, start_time),

    CONSTRAINT fk_sslot_schedule FOREIGN KEY (schedule_id) REFERENCES shift_schedules (id) ON DELETE CASCADE,
    CONSTRAINT fk_sslot_position FOREIGN KEY (position_id) REFERENCES shift_positions (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='シフト枠';
