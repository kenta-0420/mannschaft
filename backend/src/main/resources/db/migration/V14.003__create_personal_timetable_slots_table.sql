-- F03.15 個人時間割: コマ（曜日×時限→講義/教室/教員/チームリンク）
CREATE TABLE IF NOT EXISTS personal_timetable_slots (
    id                       BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    personal_timetable_id    BIGINT UNSIGNED   NOT NULL                COMMENT 'FK → personal_timetables.id',
    day_of_week              VARCHAR(3)        NOT NULL                COMMENT 'MON/TUE/WED/THU/FRI/SAT/SUN',
    period_number            TINYINT UNSIGNED  NOT NULL                COMMENT 'personal_timetable_periods.period_number',
    week_pattern             VARCHAR(5)        NOT NULL DEFAULT 'EVERY' COMMENT 'EVERY / A / B',
    subject_name             VARCHAR(200)      NOT NULL                COMMENT '講義名・教科名',
    course_code              VARCHAR(50)       NULL                    COMMENT '履修番号（大学向け）',
    teacher_name             VARCHAR(100)      NULL                    COMMENT '担当教員',
    room_name                VARCHAR(200)      NULL                    COMMENT '教室・建物名',
    credits                  DECIMAL(3,1)      NULL                    COMMENT '単位数（0.0〜9.9）',
    color                    VARCHAR(7)        NULL                    COMMENT 'HEX カラー',
    linked_team_id           BIGINT UNSIGNED   NULL                    COMMENT 'FK → teams.id（リンク先チーム）',
    linked_timetable_id      BIGINT UNSIGNED   NULL                    COMMENT 'FK → timetables.id（リンク先チーム時間割）',
    linked_slot_id           BIGINT UNSIGNED   NULL                    COMMENT 'FK → timetable_slots.id（リンク先コマ）',
    auto_sync_changes        BOOLEAN           NOT NULL DEFAULT TRUE   COMMENT '臨時変更を個人カレンダーに自動反映するか',
    notes                    VARCHAR(300)      NULL                    COMMENT 'コマ自体の備考（個人メモとは別）',
    created_at               DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_pts_day_period_week (personal_timetable_id, day_of_week, period_number, week_pattern),
    INDEX idx_pts_linked_slot (linked_slot_id),
    INDEX idx_pts_linked_team (linked_team_id),

    CONSTRAINT fk_pts_personal_timetable FOREIGN KEY (personal_timetable_id) REFERENCES personal_timetables(id) ON DELETE CASCADE,
    CONSTRAINT fk_pts_linked_team        FOREIGN KEY (linked_team_id)        REFERENCES teams(id)             ON DELETE SET NULL,
    CONSTRAINT fk_pts_linked_timetable   FOREIGN KEY (linked_timetable_id)   REFERENCES timetables(id)        ON DELETE SET NULL,
    CONSTRAINT fk_pts_linked_slot        FOREIGN KEY (linked_slot_id)        REFERENCES timetable_slots(id)   ON DELETE SET NULL,
    CONSTRAINT chk_pts_day_of_week  CHECK (day_of_week IN ('MON','TUE','WED','THU','FRI','SAT','SUN')),
    CONSTRAINT chk_pts_week_pattern CHECK (week_pattern IN ('EVERY','A','B'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F03.15 個人時間割のコマ';
