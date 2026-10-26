-- F03.9: 時間割管理 - 時間割コマ（曜日×時限→教科/担当/教室）
CREATE TABLE timetable_slots (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    timetable_id    BIGINT UNSIGNED NOT NULL,
    day_of_week     VARCHAR(3)      NOT NULL COMMENT 'MON/TUE/WED/THU/FRI/SAT/SUN',
    period_number   TINYINT UNSIGNED NOT NULL COMMENT '時限番号',
    week_pattern    VARCHAR(5)      NOT NULL DEFAULT 'EVERY' COMMENT 'EVERY/A/B（A/B週パターン）',
    subject_name    VARCHAR(100)    NOT NULL COMMENT '教科名',
    teacher_name    VARCHAR(100)    NULL     COMMENT '担当者名',
    room_name       VARCHAR(100)    NULL     COMMENT '教室・場所',
    color           VARCHAR(7)      NULL     COMMENT '表示色（HEXカラーコード）',
    notes           VARCHAR(300)    NULL     COMMENT '備考',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ts_timetable_day_period_week (timetable_id, day_of_week, period_number, week_pattern),
    INDEX idx_ts_timetable_day (timetable_id, day_of_week),
    CONSTRAINT fk_ts_timetable FOREIGN KEY (timetable_id) REFERENCES timetables (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
