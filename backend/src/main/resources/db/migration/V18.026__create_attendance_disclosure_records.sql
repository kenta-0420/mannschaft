CREATE TABLE attendance_disclosure_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    evaluation_id BIGINT UNSIGNED NOT NULL COMMENT '対象評価ID',
    student_user_id BIGINT UNSIGNED NOT NULL COMMENT '対象生徒のユーザーID',
    decision ENUM('DISCLOSED','WITHHELD') NOT NULL COMMENT '開示判断（開示/非開示）',
    mode ENUM('WITH_NUMBERS','WITHOUT_NUMBERS','MEETING_REQUEST_ONLY') NULL
        COMMENT '開示モード（DISCLOSED時のみ）',
    recipients ENUM('STUDENT_ONLY','GUARDIAN_ONLY','BOTH') NULL
        COMMENT '通知先（DISCLOSED時のみ）',
    message LONGTEXT NULL COMMENT '担任メッセージ（AES-256-GCM暗号化）',
    withhold_reason LONGTEXT NULL COMMENT '非開示理由（AES-256-GCM暗号化・WITHHELD時のみ）',
    decided_by BIGINT UNSIGNED NOT NULL COMMENT '判断者のユーザーID（担任等）',
    decided_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '判断日時',
    notification_id BIGINT UNSIGNED NULL COMMENT '通知配信レコードID（DISCLOSED時のみ・将来拡張）',
    PRIMARY KEY (id),
    INDEX idx_adr_evaluation (evaluation_id, decided_at),
    INDEX idx_adr_student (student_user_id, decided_at),
    CONSTRAINT fk_adr_evaluation
        FOREIGN KEY (evaluation_id) REFERENCES attendance_requirement_evaluations(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出席要件開示判断記録（F03.13 Phase 15）';
