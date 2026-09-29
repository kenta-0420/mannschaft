-- CMP-260924-0010 U3: BaseEntity 系 15 テーブルに欠けていた created_at / updated_at を追加する
-- （凍結台帳 KNOWN_UNPAID_DRIFT の返済）。
--
-- BaseEntity は全継承 Entity に createdAt / updatedAt を持たせ @PrePersist / @PreUpdate で必ず書き込むが、
-- 以下のテーブルの CREATE TABLE は片方または両方を作っていなかった。Flyway で構築した環境
-- （本番は ddl-auto:none）では Hibernate が存在しない列を含む SQL を発行し Unknown column で失敗していた。
--   updated_at のみ欠落（12）: ad_conversions, analytics_alert_history, attendance_transition_alerts,
--     budget_transaction_attachments, chart_body_marks, chart_photos, committee_distribution_logs,
--     job_check_ins, line_message_logs, onboarding_step_completions, parking_applications,
--     webhook_event_subscriptions
--   created_at のみ欠落（2）: daily_attendance_records, period_attendance_records
--   両方欠落（1）: proxy_votes
--
-- 列定義は既存慣行（V5.008 等）に揃える:
--   created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
--   updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
--
-- 既存行の埋め戻し（DEFAULT の migration 実行時刻のままだと updated_at が created_at より新しい嘘の値になるため）:
--   updated_at <- created_at / 出欠 2 表の created_at <- recorded_at / proxy_votes の両列 <- voted_at
--   元列はいずれも NOT NULL（V10.065 ほか各 CREATE TABLE）のため COALESCE は不要。
--   埋め戻しは「この migration で新しく足した列」に対してだけ行う（@cN = 0 のときだけ UPDATE）。
--   途中まで列が足された環境で再実行しても、既に在った列の値は上書きしない。
--   UPDATE では updated_at を必ず明示 SET する（updated_at = updated_at を含む）。明示しないと
--   ON UPDATE CURRENT_TIMESTAMP が発火し、既存の updated_at が migration 実行時刻に書き換わる。
--
-- 冪等: 列ごとに information_schema で存在を確かめてから足す（手本は V18.030）。
-- 注意: 番人テスト FlywayUnpaidDriftRepaymentMigrationTest が本ファイルを ; で分割して再実行するため、
-- 文字列リテラル内に ; を書かないこと。
-- ad_conversions.updated_at（created_at から埋め戻し）
SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ad_conversions' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c1 = 0, 'ALTER TABLE ad_conversions ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c1 = 0, 'UPDATE ad_conversions SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- analytics_alert_history.updated_at（created_at から埋め戻し）
SET @c2 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analytics_alert_history' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c2 = 0, 'ALTER TABLE analytics_alert_history ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c2 = 0, 'UPDATE analytics_alert_history SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- attendance_transition_alerts.updated_at（created_at から埋め戻し）
SET @c3 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attendance_transition_alerts' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c3 = 0, 'ALTER TABLE attendance_transition_alerts ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c3 = 0, 'UPDATE attendance_transition_alerts SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- budget_transaction_attachments.updated_at（created_at から埋め戻し）
SET @c4 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'budget_transaction_attachments' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c4 = 0, 'ALTER TABLE budget_transaction_attachments ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c4 = 0, 'UPDATE budget_transaction_attachments SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- chart_body_marks.updated_at（created_at から埋め戻し）
SET @c5 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart_body_marks' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c5 = 0, 'ALTER TABLE chart_body_marks ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c5 = 0, 'UPDATE chart_body_marks SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- chart_photos.updated_at（created_at から埋め戻し）
SET @c6 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart_photos' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c6 = 0, 'ALTER TABLE chart_photos ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c6 = 0, 'UPDATE chart_photos SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- committee_distribution_logs.updated_at（created_at から埋め戻し）
SET @c7 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'committee_distribution_logs' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c7 = 0, 'ALTER TABLE committee_distribution_logs ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c7 = 0, 'UPDATE committee_distribution_logs SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- job_check_ins.updated_at（created_at から埋め戻し）
SET @c8 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'job_check_ins' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c8 = 0, 'ALTER TABLE job_check_ins ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c8 = 0, 'UPDATE job_check_ins SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- line_message_logs.updated_at（created_at から埋め戻し）
SET @c9 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'line_message_logs' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c9 = 0, 'ALTER TABLE line_message_logs ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c9 = 0, 'UPDATE line_message_logs SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- onboarding_step_completions.updated_at（created_at から埋め戻し）
SET @c10 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'onboarding_step_completions' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c10 = 0, 'ALTER TABLE onboarding_step_completions ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c10 = 0, 'UPDATE onboarding_step_completions SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- parking_applications.updated_at（created_at から埋め戻し）
SET @c11 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'parking_applications' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c11 = 0, 'ALTER TABLE parking_applications ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c11 = 0, 'UPDATE parking_applications SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- webhook_event_subscriptions.updated_at（created_at から埋め戻し）
SET @c12 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'webhook_event_subscriptions' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c12 = 0, 'ALTER TABLE webhook_event_subscriptions ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c12 = 0, 'UPDATE webhook_event_subscriptions SET updated_at = created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- daily_attendance_records.created_at（recorded_at から埋め戻し。既存 updated_at は明示 SET で保つ）
SET @c13 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'daily_attendance_records' AND COLUMN_NAME = 'created_at');
SET @s = IF(@c13 = 0, 'ALTER TABLE daily_attendance_records ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER recorded_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c13 = 0, 'UPDATE daily_attendance_records SET created_at = recorded_at, updated_at = updated_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- period_attendance_records.created_at（recorded_at から埋め戻し。既存 updated_at は明示 SET で保つ）
SET @c14 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'period_attendance_records' AND COLUMN_NAME = 'created_at');
SET @s = IF(@c14 = 0, 'ALTER TABLE period_attendance_records ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER recorded_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c14 = 0, 'UPDATE period_attendance_records SET created_at = recorded_at, updated_at = updated_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- proxy_votes.created_at / updated_at（両列とも voted_at から埋め戻し）
SET @c15 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'proxy_votes' AND COLUMN_NAME = 'created_at');
SET @c16 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'proxy_votes' AND COLUMN_NAME = 'updated_at');
SET @s = IF(@c15 = 0, 'ALTER TABLE proxy_votes ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER voted_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c16 = 0, 'ALTER TABLE proxy_votes ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c15 = 0, 'UPDATE proxy_votes SET created_at = voted_at, updated_at = updated_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @s = IF(@c16 = 0, 'UPDATE proxy_votes SET updated_at = voted_at', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
