-- F03.15 個人時間割: 時限定義（個人時間割ごとの時限割り）
CREATE TABLE IF NOT EXISTS personal_timetable_periods (
    id                       BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    personal_timetable_id    BIGINT UNSIGNED   NOT NULL                COMMENT 'FK → personal_timetables.id',
    period_number            TINYINT UNSIGNED  NOT NULL                COMMENT '時限番号（1〜15）',
    label                    VARCHAR(50)       NOT NULL                COMMENT '表示名（例: "1限", "夜間1"）',
    start_time               TIME              NOT NULL                COMMENT '開始時刻',
    end_time                 TIME              NOT NULL                COMMENT '終了時刻',
    is_break                 BOOLEAN           NOT NULL DEFAULT FALSE  COMMENT '休憩枠（コマ割当不可）',
    created_at               DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_ptp_pt_period (personal_timetable_id, period_number),

    CONSTRAINT fk_ptp_personal_timetable FOREIGN KEY (personal_timetable_id) REFERENCES personal_timetables(id) ON DELETE CASCADE,
    CONSTRAINT chk_ptp_period_range CHECK (period_number BETWEEN 1 AND 15),
    CONSTRAINT chk_ptp_time_range CHECK (start_time < end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F03.15 個人時間割の時限定義';
