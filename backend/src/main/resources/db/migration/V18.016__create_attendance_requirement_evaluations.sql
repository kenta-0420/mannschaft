-- F03.13 Phase 12: 出席要件評価テーブル
CREATE TABLE IF NOT EXISTS attendance_requirement_evaluations (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    requirement_rule_id         BIGINT UNSIGNED NOT NULL COMMENT 'FK→attendance_requirement_rules.id',
    student_user_id             BIGINT UNSIGNED NOT NULL COMMENT '評価対象生徒 FK→users.id',
    summary_id                  BIGINT UNSIGNED NOT NULL COMMENT '元となった集計 FK→student_attendance_summaries.id',
    status                      VARCHAR(16)     NOT NULL COMMENT 'OK | WARNING | RISK | VIOLATION',
    current_attendance_rate     DECIMAL(5,2)    NOT NULL DEFAULT 0.00 COMMENT '評価時点の出席率（%）',
    remaining_allowed_absences  INT             NOT NULL DEFAULT 0    COMMENT 'あと何日休めるか（0以下=違反）',
    evaluated_at                DATETIME        NOT NULL              COMMENT '評価実施日時',
    notified_user_ids           JSON            NULL                  COMMENT '通知済みユーザーIDのJSONアレイ',
    resolved_at                 DATETIME        NULL                  COMMENT '違反解消日時',
    resolution_note             VARCHAR(512)    NULL                  COMMENT '解消理由',
    resolver_user_id            BIGINT UNSIGNED NULL                  COMMENT '解消した教員ID FK→users.id',
    created_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at                  DATETIME        NULL,

    PRIMARY KEY (id),
    INDEX idx_are_student_time  (student_user_id, evaluated_at DESC),
    INDEX idx_are_rule_student  (requirement_rule_id, student_user_id, evaluated_at DESC),
    INDEX idx_are_status        (status, evaluated_at DESC),

    CONSTRAINT fk_are_rule      FOREIGN KEY (requirement_rule_id) REFERENCES attendance_requirement_rules(id),
    CONSTRAINT fk_are_student   FOREIGN KEY (student_user_id)     REFERENCES users(id),
    CONSTRAINT fk_are_summary   FOREIGN KEY (summary_id)          REFERENCES student_attendance_summaries(id),
    CONSTRAINT fk_are_resolver  FOREIGN KEY (resolver_user_id)    REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='出席要件評価結果（F03.13 Phase 12）';
