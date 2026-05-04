-- V18.021 が先に実行済みの環境でも冪等に動作するよう information_schema で存在チェック。
-- （MySQL 8.0 は ADD COLUMN IF NOT EXISTS 非対応のため PREPARE/EXECUTE で実現）

SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='announcement_read_status' AND COLUMN_NAME='is_proxy_confirmed');
SET @s1 = IF(@c1=0, 'ALTER TABLE announcement_read_status ADD COLUMN is_proxy_confirmed TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''代理確認フラグ（0=本人既読, 1=代理確認）'' AFTER read_at', 'SELECT 1');
PREPARE stmt FROM @s1; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c2 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='announcement_read_status' AND COLUMN_NAME='proxy_input_record_id');
SET @s2 = IF(@c2=0, 'ALTER TABLE announcement_read_status ADD COLUMN proxy_input_record_id BIGINT UNSIGNED NULL COMMENT ''代理入力記録ID（proxy_input_records.id）'' AFTER is_proxy_confirmed', 'SELECT 1');
PREPARE stmt FROM @s2; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i1 = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='announcement_read_status' AND INDEX_NAME='idx_announcement_read_status_proxy');
SET @s3 = IF(@i1=0, 'ALTER TABLE announcement_read_status ADD INDEX idx_announcement_read_status_proxy (is_proxy_confirmed)', 'SELECT 1');
PREPARE stmt FROM @s3; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @f1 = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='announcement_read_status' AND CONSTRAINT_NAME='fk_announcement_read_status_proxy');
SET @s4 = IF(@f1=0, 'ALTER TABLE announcement_read_status ADD CONSTRAINT fk_announcement_read_status_proxy FOREIGN KEY (proxy_input_record_id) REFERENCES proxy_input_records (id) ON DELETE SET NULL', 'SELECT 1');
PREPARE stmt FROM @s4; EXECUTE stmt; DEALLOCATE PREPARE stmt;
