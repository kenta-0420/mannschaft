-- V18.022 が先に実行済みの環境でも冪等に動作するよう information_schema で存在チェック。
-- （MySQL 8.0 は ADD COLUMN IF NOT EXISTS 非対応のため PREPARE/EXECUTE で実現）

SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_applications' AND COLUMN_NAME='is_proxy_input');
SET @s1 = IF(@c1=0, 'ALTER TABLE parking_applications ADD COLUMN is_proxy_input TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''代理入力フラグ（0=本人, 1=代理）'' AFTER message', 'SELECT 1');
PREPARE stmt FROM @s1; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c2 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_applications' AND COLUMN_NAME='proxy_input_record_id');
SET @s2 = IF(@c2=0, 'ALTER TABLE parking_applications ADD COLUMN proxy_input_record_id BIGINT UNSIGNED NULL COMMENT ''代理入力記録ID（proxy_input_records.id）'' AFTER is_proxy_input', 'SELECT 1');
PREPARE stmt FROM @s2; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i1 = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_applications' AND INDEX_NAME='idx_parking_applications_proxy');
SET @s3 = IF(@i1=0, 'ALTER TABLE parking_applications ADD INDEX idx_parking_applications_proxy (is_proxy_input)', 'SELECT 1');
PREPARE stmt FROM @s3; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @f1 = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_applications' AND CONSTRAINT_NAME='fk_parking_applications_proxy');
SET @s4 = IF(@f1=0, 'ALTER TABLE parking_applications ADD CONSTRAINT fk_parking_applications_proxy FOREIGN KEY (proxy_input_record_id) REFERENCES proxy_input_records (id) ON DELETE SET NULL', 'SELECT 1');
PREPARE stmt FROM @s4; EXECUTE stmt; DEALLOCATE PREPARE stmt;
