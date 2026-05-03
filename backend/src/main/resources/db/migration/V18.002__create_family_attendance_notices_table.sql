-- F03.13 学校日次・教科別出欠管理: 保護者からの欠席・遅刻連絡
-- daily_attendance_records が FK 参照するため先に作成する
CREATE TABLE IF NOT EXISTS family_attendance_notices (
    id                     BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT,
    team_id                BIGINT UNSIGNED    NOT NULL COMMENT 'クラスチーム',
    student_user_id        BIGINT UNSIGNED    NOT NULL COMMENT '生徒',
    submitter_user_id      BIGINT UNSIGNED    NOT NULL COMMENT '連絡送信者（保護者 users.id）',
    attendance_date        DATE               NOT NULL COMMENT '対象日',
    notice_type            ENUM('ABSENCE','LATE','EARLY_LEAVE','OTHER') NOT NULL COMMENT '連絡種別',
    reason                 ENUM('SICK','INJURY','FAMILY_REASON','BEREAVEMENT','INFECTIOUS_DISEASE','MENTAL_HEALTH','OFFICIAL_BUSINESS','OTHER') NULL COMMENT '欠席理由',
    reason_detail          VARCHAR(1000)      NULL     COMMENT '詳細（健康情報配慮）',
    expected_arrival_time  TIME               NULL     COMMENT '遅刻時の到着予定',
    expected_leave_time    TIME               NULL     COMMENT '早退時の早退予定',
    attached_file_keys     JSON               NULL     COMMENT '診断書等の添付ファイルキー配列',
    acknowledged_by        BIGINT UNSIGNED    NULL     COMMENT '担任が確認した user_id',
    acknowledged_at        DATETIME           NULL     COMMENT '確認日時',
    applied_to_record      BOOLEAN            NOT NULL DEFAULT FALSE COMMENT '出欠レコードに反映済みか',
    created_at             DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_fan_team_date (team_id, attendance_date, acknowledged_at),
    INDEX idx_fan_student (student_user_id, attendance_date),
    CONSTRAINT fk_fan_team          FOREIGN KEY (team_id)           REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_fan_student       FOREIGN KEY (student_user_id)   REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_fan_submitter     FOREIGN KEY (submitter_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_fan_acknowledged  FOREIGN KEY (acknowledged_by)   REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='保護者からの欠席・遅刻連絡';
