CREATE TABLE event_surveys (
    id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    schedule_id   BIGINT UNSIGNED  NOT NULL,
    question      VARCHAR(500)     NOT NULL,
    question_type VARCHAR(20)      NOT NULL DEFAULT 'BOOLEAN',
    options       JSON,
    is_required   BOOLEAN          NOT NULL DEFAULT TRUE,
    sort_order    TINYINT UNSIGNED NOT NULL DEFAULT 0,
    created_at    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_es_schedule (schedule_id),

    CONSTRAINT fk_es_schedule FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='スケジュール附帯アンケート設問';
