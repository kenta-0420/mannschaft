-- F03.13 学校日次・教科別出欠管理: 日次出欠（朝の点呼）
-- status は既存 AttendanceStatus enum に準拠: ATTENDING=出席, PARTIAL=遅刻/早退, ABSENT=欠席, UNDECIDED=未確認
CREATE TABLE daily_attendance_records (
    id                BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT,
    team_id           BIGINT UNSIGNED    NOT NULL COMMENT 'クラスチーム',
    student_user_id   BIGINT UNSIGNED    NOT NULL COMMENT '生徒',
    attendance_date   DATE               NOT NULL COMMENT '対象日',
    status            ENUM('ATTENDING','PARTIAL','ABSENT','UNDECIDED') NOT NULL DEFAULT 'UNDECIDED',
    absence_reason    ENUM('SICK','INJURY','FAMILY_REASON','BEREAVEMENT','INFECTIOUS_DISEASE','MENTAL_HEALTH','OFFICIAL_BUSINESS','OTHER') NULL COMMENT 'status=ABSENT/PARTIAL 時',
    arrival_time      TIME               NULL COMMENT '実際の登校時刻（PARTIAL=遅刻の場合に記録）',
    leave_time        TIME               NULL COMMENT '早退時刻（PARTIAL=早退の場合に記録）',
    comment           VARCHAR(500)       NULL COMMENT '担任コメント',
    family_notice_id  BIGINT UNSIGNED    NULL COMMENT 'FK → family_attendance_notices.id（保護者連絡の参照）',
    recorded_by       BIGINT UNSIGNED    NOT NULL COMMENT '記録者（担任 user_id）',
    recorded_at       DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT uq_dar UNIQUE (team_id, student_user_id, attendance_date),
    INDEX idx_dar_date (attendance_date, team_id),
    INDEX idx_dar_student (student_user_id, attendance_date),
    CONSTRAINT fk_dar_team          FOREIGN KEY (team_id)          REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_dar_student       FOREIGN KEY (student_user_id)  REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_dar_recorded_by   FOREIGN KEY (recorded_by)      REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_dar_family_notice FOREIGN KEY (family_notice_id) REFERENCES family_attendance_notices(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日次出欠（朝の点呼記録）';
