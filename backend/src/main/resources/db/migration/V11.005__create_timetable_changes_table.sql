-- F03.9: 時間割管理 - 臨時変更（授業変更・休講・補講・日単位一括休講）
CREATE TABLE timetable_changes (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    timetable_id    BIGINT UNSIGNED NOT NULL,
    target_date     DATE            NOT NULL COMMENT '変更対象日',
    period_number   TINYINT UNSIGNED NULL    COMMENT '変更対象時限（NULL=全コマ対象: DAY_OFF時）',
    change_type     VARCHAR(10)     NOT NULL COMMENT 'REPLACE/CANCEL/ADD/DAY_OFF',
    subject_name    VARCHAR(100)    NULL     COMMENT '変更後の教科名（REPLACE/ADD時に必須）',
    teacher_name    VARCHAR(100)    NULL     COMMENT '変更後の担当者名',
    room_name       VARCHAR(100)    NULL     COMMENT '変更後の教室',
    reason          VARCHAR(300)    NULL     COMMENT '変更理由',
    notify_members  BOOLEAN         NOT NULL DEFAULT TRUE COMMENT 'メンバーへの通知要否',
    created_by      BIGINT UNSIGNED NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_tc_timetable_date_period (timetable_id, target_date, period_number),
    INDEX idx_tc_timetable_date (timetable_id, target_date),
    CONSTRAINT fk_tc_timetable FOREIGN KEY (timetable_id) REFERENCES timetables (id) ON DELETE CASCADE,
    CONSTRAINT fk_tc_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
