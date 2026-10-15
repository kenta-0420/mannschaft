-- F03.13 Phase 14 で AttendanceRequirementRuleEntity に追加されたが、
-- V18.020 が proxy_input_columns に使われてしまい欠落したカラムの後付け修正。
-- V18.020 (add_notify_violation) が正しく実行済みの環境でも冪等動作するよう
-- information_schema で存在チェックしてから ADD COLUMN を実行する。
-- （MySQL 8.0 は ADD COLUMN IF NOT EXISTS 非対応のため PREPARE/EXECUTE で実現）

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'attendance_requirement_rules'
      AND COLUMN_NAME = 'notify_violation_to_guardian_automatically'
);

SET @ddl = IF(
    @col_exists = 0,
    'ALTER TABLE attendance_requirement_rules ADD COLUMN notify_violation_to_guardian_automatically TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''VIOLATION確定時に自動で保護者通知するか（デフォルト false）''',
    'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
