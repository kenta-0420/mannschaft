-- CMP-260924-0010 U1: circulation_recipients にスキップ 3 列を追加する（凍結台帳 KNOWN_UNPAID_DRIFT の返済）。
--
-- 【訂正】V9.175__alter_circulation_recipients_add_skip_reason.sql は
-- 「skip_reason / skipped_by / skipped_at の追加は V9.171 で実施済み」と書いて SELECT 1 だけを実行しているが、これは誤り。
-- V9.171 は create_name_disclosure_change_logs で無関係であり、3 列はどの migration にも存在しなかった。
-- V9.175 は適用済みのため書き換えず、本 migration で列を追加して訂正する。
-- （Entity CirculationRecipientEntity は 3 列をマップしているため、Flyway で構築した環境では
--   回覧受信者の読み書きが Unknown column で失敗していた）
--
-- 列定義は Entity に合わせる: skipReason = @Column(length = 255) String / skippedBy = Long / skippedAt = LocalDateTime。
-- いずれも本人スキップ・未スキップでは NULL のため NULL 可。既存行は NULL のまま（埋め戻し不要）。
--
-- 冪等: 途中まで列が足された環境でも完遂できるよう、列ごとに information_schema で存在を確かめてから足す
-- （MySQL 8.0 は ADD COLUMN IF NOT EXISTS 非対応のため PREPARE/EXECUTE。手本は V18.030）。
-- 注意: 番人テスト FlywayUnpaidDriftRepaymentMigrationTest が本ファイルを ; で分割して再実行するため、
-- 文字列リテラル内に ; を書かないこと。

SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'circulation_recipients' AND COLUMN_NAME = 'skip_reason');
SET @s1 = IF(@c1 = 0, 'ALTER TABLE circulation_recipients ADD COLUMN skip_reason VARCHAR(255) NULL COMMENT ''ADMIN 強制スキップ時の理由（本人スキップは NULL）''', 'SELECT 1');
PREPARE stmt FROM @s1; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c2 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'circulation_recipients' AND COLUMN_NAME = 'skipped_by');
SET @s2 = IF(@c2 = 0, 'ALTER TABLE circulation_recipients ADD COLUMN skipped_by BIGINT UNSIGNED NULL COMMENT ''スキップ操作を行った users.id（本人スキップは NULL）''', 'SELECT 1');
PREPARE stmt FROM @s2; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c3 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'circulation_recipients' AND COLUMN_NAME = 'skipped_at');
SET @s3 = IF(@c3 = 0, 'ALTER TABLE circulation_recipients ADD COLUMN skipped_at DATETIME NULL COMMENT ''スキップ実行日時''', 'SELECT 1');
PREPARE stmt FROM @s3; EXECUTE stmt; DEALLOCATE PREPARE stmt;
