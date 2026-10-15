CREATE TABLE schedule_attendances (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    schedule_id  BIGINT UNSIGNED NOT NULL,
    user_id      BIGINT UNSIGNED NOT NULL,
    status       VARCHAR(20)     NOT NULL DEFAULT 'UNDECIDED',
    comment      VARCHAR(500),
    responded_at DATETIME,
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_sa_schedule_user (schedule_id, user_id),
    INDEX idx_sa_user_id (user_id),
    INDEX idx_sa_status (schedule_id, status),

    CONSTRAINT fk_sa_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE CASCADE,
    CONSTRAINT fk_sa_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='スケジュール出欠回答';
