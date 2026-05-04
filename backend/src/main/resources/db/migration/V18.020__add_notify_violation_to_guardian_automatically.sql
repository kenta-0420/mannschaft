-- F03.13 Phase 14: 出席要件違反時の保護者自動通知フラグを attendance_requirement_rules に追加
ALTER TABLE attendance_requirement_rules
    ADD COLUMN notify_violation_to_guardian_automatically TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'VIOLATION確定時に自動で保護者通知するか（デフォルト false）';
