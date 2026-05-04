-- F03.13 学校日次・教科別出欠管理: 「前にいたのに今いない」検知ログ
CREATE TABLE IF NOT EXISTS attendance_transition_alerts (
    id                      BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT,
    team_id                 BIGINT UNSIGNED    NOT NULL,
    student_user_id         BIGINT UNSIGNED    NOT NULL,
    attendance_date         DATE               NOT NULL,
    previous_period_number  TINYINT UNSIGNED   NOT NULL COMMENT '直前時限（出席だった）',
    current_period_number   TINYINT UNSIGNED   NOT NULL COMMENT '現在時限（欠席になった）',
    previous_period_status  ENUM('ATTENDING','PARTIAL','ABSENT','UNDECIDED') NOT NULL COMMENT '直前のステータス',
    current_period_status   ENUM('ATTENDING','PARTIAL','ABSENT','UNDECIDED') NOT NULL COMMENT '現在のステータス',
    alert_level             ENUM('NORMAL','URGENT') NOT NULL DEFAULT 'NORMAL' COMMENT 'URGENT=明らかな失踪リスク（2時限連続欠席等）',
    notified_users          JSON               NOT NULL COMMENT '通知済みユーザーID配列 [担任, 副担任, 保護者]',
    resolved_at             DATETIME           NULL     COMMENT '解決確認日時',
    resolved_by             BIGINT UNSIGNED    NULL     COMMENT '解決確認者',
    resolution_note         VARCHAR(500)       NULL     COMMENT '解決理由',
    created_at              DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_ata_unresolved (resolved_at, attendance_date),
    INDEX idx_ata_student_date (student_user_id, attendance_date),
    CONSTRAINT fk_ata_team        FOREIGN KEY (team_id)         REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_ata_student     FOREIGN KEY (student_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ata_resolved_by FOREIGN KEY (resolved_by)     REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='「前にいたのに今いない」検知ログ';
