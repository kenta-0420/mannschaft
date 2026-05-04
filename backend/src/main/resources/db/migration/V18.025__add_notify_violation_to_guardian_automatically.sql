-- F03.13 Phase 14 で AttendanceRequirementRuleEntity に追加されたが、
-- V18.020 が proxy_input_columns に使われてしまい欠落したカラムの後付け修正。
-- V18.020 (add_notify_violation) が正しく実行済みの環境でも冪等動作するよう IF NOT EXISTS を使用。
ALTER TABLE attendance_requirement_rules
    ADD COLUMN IF NOT EXISTS notify_violation_to_guardian_automatically TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'VIOLATION確定時に自動で保護者通知するか（デフォルト false）';
