-- 登校場所変更履歴テーブルを作成する

CREATE TABLE attendance_location_changes (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    team_id           BIGINT       NOT NULL,
    student_user_id   BIGINT       NOT NULL,
    attendance_date   DATE         NOT NULL,
    from_location     ENUM('CLASSROOM','SICK_BAY','SEPARATE_ROOM','LIBRARY','ONLINE','HOME_LEARNING','OUT_OF_SCHOOL','NOT_APPLICABLE') NOT NULL,
    to_location       ENUM('CLASSROOM','SICK_BAY','SEPARATE_ROOM','LIBRARY','ONLINE','HOME_LEARNING','OUT_OF_SCHOOL','NOT_APPLICABLE') NOT NULL,
    changed_at_period TINYINT      NULL,
    changed_at_time   TIME         NULL,
    reason            ENUM('FELT_SICK','INJURY','MENTAL_HEALTH','SCHEDULED','RECOVERED','RETURNED_TO_CLASS','OTHER') NOT NULL,
    note              VARCHAR(500) NULL,
    recorded_by       BIGINT       NOT NULL,
    recorded_at       DATETIME     NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_alc_student_date (student_user_id, attendance_date),
    INDEX idx_alc_team_date    (team_id, attendance_date)
);
