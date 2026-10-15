-- V18.023 が先に実行済みの環境でも冪等に動作するよう information_schema で存在チェック。
-- （MySQL 8.0 は ADD COLUMN IF NOT EXISTS 非対応のため PREPARE/EXECUTE で実現）

SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_recipients' AND COLUMN_NAME='is_proxy_confirmed');
SET @s1 = IF(@c1=0, 'ALTER TABLE circulation_recipients ADD COLUMN is_proxy_confirmed TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''代理確認押印フラグ（0=本人, 1=代理）'' AFTER stamped_at', 'SELECT 1');
PREPARE stmt FROM @s1; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c2 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_recipients' AND COLUMN_NAME='proxy_input_record_id');
SET @s2 = IF(@c2=0, 'ALTER TABLE circulation_recipients ADD COLUMN proxy_input_record_id BIGINT UNSIGNED NULL COMMENT ''代理入力記録ID（proxy_input_records.id）'' AFTER is_proxy_confirmed', 'SELECT 1');
PREPARE stmt FROM @s2; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @i1 = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_recipients' AND INDEX_NAME='idx_circulation_recipients_proxy');
SET @s3 = IF(@i1=0, 'ALTER TABLE circulation_recipients ADD INDEX idx_circulation_recipients_proxy (is_proxy_confirmed)', 'SELECT 1');
PREPARE stmt FROM @s3; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @f1 = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_recipients' AND CONSTRAINT_NAME='fk_circulation_recipients_proxy');
SET @s4 = IF(@f1=0, 'ALTER TABLE circulation_recipients ADD CONSTRAINT fk_circulation_recipients_proxy FOREIGN KEY (proxy_input_record_id) REFERENCES proxy_input_records (id) ON DELETE SET NULL', 'SELECT 1');
PREPARE stmt FROM @s4; EXECUTE stmt; DEALLOCATE PREPARE stmt;
