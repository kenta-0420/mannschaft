-- =============================================================================
-- issue #2589: 照合順序をスキーマ全体で utf8mb4_0900_ai_ci に統一する
-- =============================================================================
--
-- 【根本原因】
-- 本スキーマの照合順序は「表ごとにバラバラ」かつ「一部はサーバ変数 collation_server 依存」だった。
-- 全 726 表の内訳（db/migration の全 SQL を Flyway 版数順に合成して実測）:
--   * utf8mb4_unicode_ci を明示宣言 ... 551 表（例: notifications / V4.019）
--   * utf8mb4_0900_ai_ci を明示宣言 ... 33 表（村ドメイン一式 / V9.125 以降）
--   * 宣言なし＝サーバ既定に従う  ... 142 表（例: my_scope_folders / V9.100）
--
-- サーバ既定は環境で異なっていた:
--   * 本番 RDS       … utf8mb4_0900_ai_ci（infra/terraform/modules/data/main.tf の collation_server）
--   * ローカル docker … utf8mb4_unicode_ci（docker-compose.yml の --collation-server。本 PR で本番へ揃えた）
--
-- ⇒「宣言なしの表」の照合順序が環境ごとに変わるため、宣言なしの表と明示宣言の表を
--   JOIN して文字列列を比較すると、ローカルでは通り本番だけ Illegal mix of collations で落ちる。
--   実害: MyScopeFolderItemRepository#aggregateFolderUnreadCounts
--         （notifications.scope_type = my_scope_folders.scope_type）。
--
-- 【是正方針】
-- 対症的に当該 JOIN へ COLLATE を付けるのではなく、
--   (1) 全表の照合順序を明示的に一本化し、
--   (2) データベース既定そのものを固定して、以後 collation_server に依存しなくする
-- ことで、「環境変数によってスキーマの意味が変わる」という根本原因を除去する。
-- スキーマ側の不変条件として直すため、native / JPQL / Hibernate 生成 SQL の別を問わず
-- 全クエリが一括で救われる（個別 JOIN に COLLATE を足す方式は列挙漏れが原理的に避けられない）。
--
-- 統一先に utf8mb4_0900_ai_ci を選んだ理由:
--   * 本番 RDS の既定であり、terraform が「MySQL 8.0 標準の ICU ベース照合順序」として
--     意図的に選択している。その意図を覆さない。
--   * 宣言なしの 142 表は本番では既に utf8mb4_0900_ai_ci であり、本番側のデータ変更が発生しない。
--   * 変換方向が「粗い→細かい」になる。utf8mb4_unicode_ci(UCA 4.0.0) は 'ß'='ss' 等の
--     展開を等価とみなすが utf8mb4_0900_ai_ci(UCA 9.0.0) は区別する。
--     細かい側へ寄せる変換では等価な値の組が増えないため、
--     既存データが一意制約に新たに違反して migration が失敗することが原理的に起きない
--     （逆向きに unicode_ci へ寄せると等価判定が粗くなり、既存データで一意制約違反を招きうる）。
--
-- 【実装方針: なぜストアドプロシージャを使わないか】
-- information_schema をカーソルで回せば短く書けるが、その形は BEGIN...END を含むため
-- 素の mysql クライアントでは DELIMITER 指定なしに構文エラーになる（実測で確認済み）。
-- migration は Flyway 以外（障害時の手動適用・DBA によるレビュー）からも読まれ・流されるため、
-- パーサ依存の書き方を避け、既存 migration（V18.029 等）と同じ
-- SET @… / PREPARE / EXECUTE の冪等イディオムを表ごとに展開する。
--
-- 【冪等性・環境適応】
-- 各表について「現在の照合順序が統一先と違うときだけ」ALTER を発行する。
-- そのため本番では約 551 表、ローカルでは約 693 表が実際に変換されるが、
-- SQL は同一で結果も同一に収束する。再実行しても対象が 0 件になるだけで安全。
-- 既に削除された表・未作成の表は information_schema に無いため単に何もしない。
-- =============================================================================

-- データベース既定を固定する。これ以降に作られる表は、
-- サーバ変数 collation_server が何であろうと統一先を継承する（根本原因の除去）。
-- 名前を省略すると既定データベース（＝Flyway の接続先）が対象になる。
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 外部キーは参照元・参照先の文字列列の照合順序が一致していないと張れない。
-- 表を 1 枚ずつ変換する過程では両者が一時的に食い違うため、変換中だけ検査を止める。
-- これはエラーの握りつぶしではなく「途中状態を経由するための一時停止」であり、
-- 全表の変換後は同一照合順序に揃うので整合性は回復する
-- （回復していることは末尾の検証ブロックが機械的に確認し、駄目なら migration ごと失敗させる）。
SET @prev_fk_checks = @@SESSION.foreign_key_checks;
SET SESSION foreign_key_checks = 0;

-- -----------------------------------------------------------------------------
-- 全 726 表の変換（現在の照合順序が統一先と異なる表だけ ALTER する）
-- -----------------------------------------------------------------------------
SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='action_memo_tag_links' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `action_memo_tag_links` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='action_memo_tags' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `action_memo_tags` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='action_memos' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `action_memos` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='active_contract_pointers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `active_contract_pointers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_comments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `activity_comments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_feed' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `activity_feed` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_participants' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `activity_participants` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_results' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `activity_results` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_template_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `activity_template_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `activity_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_announcement_deliveries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_announcement_deliveries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_audience_segments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_audience_segments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_banner_deliveries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_banner_deliveries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_campaign_moderation_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_campaign_moderation_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_campaigns' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_campaigns` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_clicks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_clicks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_conversions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_conversions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_credit_limit_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_credit_limit_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_daily_stats' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_daily_stats` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_email_deliveries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_email_deliveries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_impressions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_impressions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_invoice_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_invoice_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_invoices' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_invoices` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_messaging_campaign_channels' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_messaging_campaign_channels` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_messaging_campaigns' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_messaging_campaigns` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_ng_words' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_ng_words` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_push_deliveries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_push_deliveries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_rate_cards' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_rate_cards` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_report_schedules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_report_schedules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_targeting_rules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_targeting_rules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ad_user_reports' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ad_user_reports` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_action_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `admin_action_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ads' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ads` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='advertiser_accounts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `advertiser_accounts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='affiliate_configs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `affiliate_configs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='age_group_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `age_group_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='analytics_alert_history' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `analytics_alert_history` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='analytics_alert_rules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `analytics_alert_rules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='analytics_daily_ads' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `analytics_daily_ads` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='analytics_daily_modules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `analytics_daily_modules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='analytics_daily_revenue' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `analytics_daily_revenue` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='analytics_daily_users' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `analytics_daily_users` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='analytics_funnel_snapshots' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `analytics_funnel_snapshots` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='analytics_monthly_cohorts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `analytics_monthly_cohorts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='analytics_monthly_snapshots' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `analytics_monthly_snapshots` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='announcement_feeds' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `announcement_feeds` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='announcement_range_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `announcement_range_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='announcement_read_status' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `announcement_read_status` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='annual_review_responses' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `annual_review_responses` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='annual_reviews' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `annual_reviews` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='api_keys' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `api_keys` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='appearance_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `appearance_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attendance_disclosure_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `attendance_disclosure_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attendance_location_changes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `attendance_location_changes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attendance_requirement_evaluations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `attendance_requirement_evaluations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attendance_requirement_rules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `attendance_requirement_rules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='attendance_transition_alerts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `attendance_transition_alerts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='audit_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `audit_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='badges' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `badges` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='batch_job_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `batch_job_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='beta_grants' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `beta_grants` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='beta_perk_criteria' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `beta_perk_criteria` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='beta_restriction_config' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `beta_restriction_config` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='billing_contracts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `billing_contracts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='blog_media_uploads' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `blog_media_uploads` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='blog_post_reactions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `blog_post_reactions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='blog_post_revisions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `blog_post_revisions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='blog_post_series' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `blog_post_series` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='blog_post_shares' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `blog_post_shares` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='blog_post_tags' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `blog_post_tags` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='blog_posts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `blog_posts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='blog_tags' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `blog_tags` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='board_handover_packs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `board_handover_packs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='budget_allocations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `budget_allocations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='budget_categories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `budget_categories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='budget_configs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `budget_configs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='budget_fiscal_years' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `budget_fiscal_years` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='budget_reports' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `budget_reports` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='budget_threshold_alerts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `budget_threshold_alerts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='budget_transaction_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `budget_transaction_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='budget_transactions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `budget_transactions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bulletin_archive_folders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `bulletin_archive_folders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bulletin_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `bulletin_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bulletin_categories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `bulletin_categories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bulletin_reactions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `bulletin_reactions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bulletin_read_status' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `bulletin_read_status` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bulletin_replies' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `bulletin_replies` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bulletin_threads' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `bulletin_threads` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_body_marks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_body_marks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_custom_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_custom_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_custom_values' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_custom_values` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_formulas' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_formulas` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_intake_form_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_intake_form_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_intake_forms' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_intake_forms` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_photos' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_photos` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_record_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_record_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chart_section_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chart_section_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_channel_members' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chat_channel_members` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_channels' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chat_channels` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_contact_folder_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chat_contact_folder_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_contact_folders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chat_contact_folders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_message_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chat_message_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_message_bookmarks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chat_message_bookmarks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_message_reactions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chat_message_reactions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_messages' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chat_messages` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_messages_archive' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `chat_messages_archive` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='checkin_locations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `checkin_locations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `circulation_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_comments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `circulation_comments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_documents' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `circulation_documents` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_recipients' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `circulation_recipients` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_stamp_correction_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `circulation_stamp_correction_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='circulation_stamp_delegations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `circulation_stamp_delegations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='cities' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `cities` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='class_homerooms' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `class_homerooms` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coin_toss_results' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `coin_toss_results` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='committee_distribution_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `committee_distribution_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='committee_invitations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `committee_invitations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='committee_members' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `committee_members` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='committees' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `committees` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='confirmable_notification_recipients' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `confirmable_notification_recipients` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='confirmable_notification_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `confirmable_notification_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='confirmable_notification_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `confirmable_notification_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='confirmable_notifications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `confirmable_notifications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='connect_accounts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `connect_accounts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contact_invite_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `contact_invite_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contact_request_blocks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `contact_request_blocks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contact_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `contact_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='content_payment_gates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `content_payment_gates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='content_reports' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `content_reports` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='content_reports_archive' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `content_reports_archive` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='content_translations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `content_translations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='corkboard_card_groups' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `corkboard_card_groups` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='corkboard_cards' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `corkboard_cards` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='corkboard_groups' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `corkboard_groups` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='corkboards' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `corkboards` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon_distributions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `coupon_distributions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupon_redemptions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `coupon_redemptions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='coupons' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `coupons` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='csp_reports' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `csp_reports` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='daily_attendance_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `daily_attendance_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dashboard_scope_tab_order' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `dashboard_scope_tab_order` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dashboard_widget_role_visibility' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `dashboard_widget_role_visibility` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dashboard_widget_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `dashboard_widget_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='data_exports' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `data_exports` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='delinquency_escalations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `delinquency_escalations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='direct_mail_image_uploads' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `direct_mail_image_uploads` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='direct_mail_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `direct_mail_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='direct_mail_logs_archive' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `direct_mail_logs_archive` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='direct_mail_recipients' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `direct_mail_recipients` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='direct_mail_recipients_archive' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `direct_mail_recipients_archive` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='direct_mail_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `direct_mail_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='disclosure_auto_delete_batch_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `disclosure_auto_delete_batch_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='disclosure_exports' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `disclosure_exports` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='disclosure_form_drafts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `disclosure_form_drafts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='disclosure_form_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `disclosure_form_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='duty_rotations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `duty_rotations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='dwelling_units' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `dwelling_units` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='electronic_seals' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `electronic_seals` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='email_change_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `email_change_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='email_outbox' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `email_outbox` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='email_verification_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `email_verification_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='emergency_closure_confirmations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `emergency_closure_confirmations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='emergency_closures' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `emergency_closures` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='entitlements' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `entitlements` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='equipment_assignments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `equipment_assignments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='equipment_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `equipment_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='equipment_ranking_exclusions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `equipment_ranking_exclusions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='equipment_rankings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `equipment_rankings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='error_report_activities' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `error_report_activities` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='error_report_ai_analyses' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `error_report_ai_analyses` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='error_report_occurrences' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `error_report_occurrences` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='error_reports' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `error_reports` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='escrow_transactions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `escrow_transactions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_care_notification_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_care_notification_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_checkins' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_checkins` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_delegations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_delegations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_guest_invite_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_guest_invite_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_registrations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_registrations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_rsvp_responses' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_rsvp_responses` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_survey_responses' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_survey_responses` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_surveys' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_surveys` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_ticket_types' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_ticket_types` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_tickets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_tickets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='event_timetable_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `event_timetable_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='events' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `events` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='external_agent_delegations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `external_agent_delegations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='facility_booking_daily_stats' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `facility_booking_daily_stats` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='facility_booking_equipment' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `facility_booking_equipment` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='facility_booking_payments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `facility_booking_payments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='facility_bookings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `facility_bookings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='facility_equipment' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `facility_equipment` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='facility_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `facility_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='facility_time_rates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `facility_time_rates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='facility_usage_rules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `facility_usage_rules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='family_attendance_notices' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `family_attendance_notices` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='feature_catalog' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `feature_catalog` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='feature_flags' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `feature_flags` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fee_policies' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `fee_policies` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fee_policy_assignments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `fee_policy_assignments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fee_recovery_balances' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `fee_recovery_balances` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='feedback_submissions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `feedback_submissions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='feedback_votes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `feedback_votes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='file_permissions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `file_permissions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='follows' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `follows` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='form_submission_values' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `form_submission_values` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='form_submissions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `form_submissions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='form_template_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `form_template_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='form_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `form_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='friend_content_forwards' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `friend_content_forwards` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gamification_configs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `gamification_configs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gamification_user_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `gamification_user_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gdpr_s3_purge_failures' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `gdpr_s3_purge_failures` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='geonames_metadata' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `geonames_metadata` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='google_calendar_webhook_channels' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `google_calendar_webhook_channels` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='guardianship_transition_notifications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `guardianship_transition_notifications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='holiday_master' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `holiday_master` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inbox_item_states' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `inbox_item_states` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='inbox_label_links' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `inbox_label_links` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incident_assignments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incident_assignments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incident_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incident_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incident_banner_translations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incident_banner_translations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incident_banners' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incident_banners` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incident_categories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incident_categories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incident_comment_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incident_comment_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incident_comments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incident_comments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incident_maintenance_schedules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incident_maintenance_schedules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incident_status_histories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incident_status_histories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incidents' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incidents` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='incoming_webhook_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `incoming_webhook_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='invite_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `invite_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='job_applications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `job_applications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='job_check_ins' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `job_check_ins` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='job_contracts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `job_contracts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='job_postings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `job_postings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='job_qr_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `job_qr_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_image_uploads' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `kb_image_uploads` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_page_favorites' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `kb_page_favorites` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_page_pins' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `kb_page_pins` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_page_revisions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `kb_page_revisions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_pages' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `kb_pages` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='kb_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `kb_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='league_transfer' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `league_transfer` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ledger_entries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ledger_entries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='legal_filings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `legal_filings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='line_bot_configs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `line_bot_configs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='line_message_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `line_message_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='maintenance_schedules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `maintenance_schedules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_events' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_events` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_notification_preferences' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_notification_preferences` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_proposal_dates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_proposal_dates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_proposals' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_proposals` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_request_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_request_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_reviews' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_reviews` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_roster_staff' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_roster_staff` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_score_entries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_score_entries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_scored_components' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_scored_components` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='match_sets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `match_sets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='matches' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `matches` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_availability_defaults' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `member_availability_defaults` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_card_checkins' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `member_card_checkins` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_cards' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `member_cards` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_payments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `member_payments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_positions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `member_positions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_profile_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `member_profile_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_profiles' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `member_profiles` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_skills' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `member_skills` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='member_work_constraints' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `member_work_constraints` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='membership_subscriptions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `membership_subscriptions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='memberships' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `memberships` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mentions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `mentions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mfa_recovery_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `mfa_recovery_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='moderation_action_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `moderation_action_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='moderation_appeals' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `moderation_appeals` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='moderation_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `moderation_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='moderation_settings_history' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `moderation_settings_history` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='module_definitions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `module_definitions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='module_level_availability' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `module_level_availability` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='module_recommendations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `module_recommendations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='monitoring_committee_visits' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `monitoring_committee_visits` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='multipart_upload_sessions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `multipart_upload_sessions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='my_scope_folder_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `my_scope_folder_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='my_scope_folders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `my_scope_folders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nav_features' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `nav_features` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ng_teams' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ng_teams` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_credit_packages' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notification_credit_packages` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_credit_purchases' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notification_credit_purchases` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_delivery_stats' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notification_delivery_stats` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_fanout_jobs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notification_fanout_jobs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_labels' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notification_labels` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_monthly_usage' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notification_monthly_usage` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_preferences' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notification_preferences` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notification_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notification_type_preferences' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notification_type_preferences` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notifications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notifications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notifications_archive' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `notifications_archive` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='oauth_accounts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `oauth_accounts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='oauth_link_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `oauth_link_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='offline_sync_conflicts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `offline_sync_conflicts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='onboarding_progresses' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `onboarding_progresses` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='onboarding_step_completions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `onboarding_step_completions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='onboarding_template_steps' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `onboarding_template_steps` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='onboarding_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `onboarding_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='org_wide_safety_checks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `org_wide_safety_checks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organization_access_requirements' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `organization_access_requirements` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organization_blocks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `organization_blocks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organization_custom_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `organization_custom_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organization_enabled_modules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `organization_enabled_modules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organization_name_disclosure_change_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `organization_name_disclosure_change_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organization_notification_balances' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `organization_notification_balances` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organization_officers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `organization_officers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organization_slug_history' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `organization_slug_history` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='organizations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `organizations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='otp_challenges' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `otp_challenges` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='page_view_daily_stats' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `page_view_daily_stats` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='page_view_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `page_view_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parental_consent_links' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parental_consent_links` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_applications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_applications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_assignments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_assignments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_listings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_listings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_space_price_history' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_space_price_history` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_spaces' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_spaces` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_sublease_applications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_sublease_applications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_sublease_payments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_sublease_payments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_subleases' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_subleases` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_visitor_recurring' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_visitor_recurring` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_visitor_reservations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_visitor_reservations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='parking_watchlist' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `parking_watchlist` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='password_reset_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `password_reset_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_beneficiary_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `payment_beneficiary_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `payment_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_proxy_grants' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `payment_proxy_grants` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `payment_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='pending_uploads' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `pending_uploads` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='performance_metric_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `performance_metric_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='performance_metrics' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `performance_metrics` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='performance_monthly_summaries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `performance_monthly_summaries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='performance_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `performance_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='period_attendance_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `period_attendance_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='permission_group_permissions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `permission_group_permissions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='permission_groups' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `permission_groups` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='permissions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `permissions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='personal_schedule_reminders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `personal_schedule_reminders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='personal_timetable_periods' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `personal_timetable_periods` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='personal_timetable_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `personal_timetable_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='personal_timetable_share_targets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `personal_timetable_share_targets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='personal_timetable_slots' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `personal_timetable_slots` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='personal_timetables' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `personal_timetables` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='photo_albums' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `photo_albums` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='photos' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `photos` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='plan_features' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `plan_features` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='plan_price_bands' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `plan_price_bands` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='plans' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `plans` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='platform_announcements' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `platform_announcements` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='player_appearances' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `player_appearances` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_card_balance_events' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `point_card_balance_events` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_card_group_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `point_card_group_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_card_groups' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `point_card_groups` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_card_provider_synonyms' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `point_card_provider_synonyms` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_card_providers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `point_card_providers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_card_stamp_events' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `point_card_stamp_events` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_card_user_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `point_card_user_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_rules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `point_rules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='point_transactions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `point_transactions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='positions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `positions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='postal_codes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `postal_codes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='prefectures' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `prefectures` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='presence_events' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `presence_events` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='project_milestones' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `project_milestones` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='projects' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `projects` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='promotion_billing_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `promotion_billing_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='promotion_deliveries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `promotion_deliveries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='promotion_delivery_summaries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `promotion_delivery_summaries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='promotion_segments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `promotion_segments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='promotions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `promotions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='property_listing_inquiries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `property_listing_inquiries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='property_listings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `property_listings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='property_work_documents' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `property_work_documents` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='property_work_history_views' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `property_work_history_views` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='property_work_packages' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `property_work_packages` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='proxy_delegations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `proxy_delegations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='proxy_input_consent_scopes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `proxy_input_consent_scopes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='proxy_input_consents' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `proxy_input_consents` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='proxy_input_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `proxy_input_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='proxy_vote_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `proxy_vote_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='proxy_vote_motion_comments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `proxy_vote_motion_comments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='proxy_vote_motions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `proxy_vote_motions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='proxy_vote_sessions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `proxy_vote_sessions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='proxy_votes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `proxy_votes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='public_faqs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `public_faqs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='public_post_comments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `public_post_comments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='push_subscriptions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `push_subscriptions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='queue_categories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `queue_categories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='queue_counters' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `queue_counters` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='queue_daily_stats' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `queue_daily_stats` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='queue_qr_codes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `queue_qr_codes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='queue_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `queue_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='queue_tickets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `queue_tickets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='quick_memo_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `quick_memo_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='quick_memo_tag_links' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `quick_memo_tag_links` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='quick_memos' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `quick_memos` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ranking_snapshots' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ranking_snapshots` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recall_attempts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recall_attempts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='receipt_issuer_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `receipt_issuer_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='receipt_line_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `receipt_line_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='receipt_presets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `receipt_presets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='receipt_queue' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `receipt_queue` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='receipts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `receipts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_cancellation_policies' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_cancellation_policies` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_cancellation_policy_tiers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_cancellation_policy_tiers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_cancellation_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_cancellation_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_categories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_categories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_distribution_targets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_distribution_targets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_friend_targets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_friend_targets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_listing_regions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_listing_regions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_listings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_listings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_no_show_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_no_show_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_participant_history' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_participant_history` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_participants' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_participants` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_penalty_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_penalty_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_reminders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_reminders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_subcategories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_subcategories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='recruitment_user_penalties' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `recruitment_user_penalties` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reflection_entries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reflection_entries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reflection_spaced_reminders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reflection_spaced_reminders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reflection_themes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reflection_themes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='refresh_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `refresh_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='refunds' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `refunds` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='region_translations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `region_translations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='registered_vehicles' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `registered_vehicles` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='repair_plan_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `repair_plan_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='repair_plan_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `repair_plan_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='repair_quote_cards' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `repair_quote_cards` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='repair_quote_kanbans' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `repair_quote_kanbans` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='repair_simulation_scenario_versions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `repair_simulation_scenario_versions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='repair_simulation_scenarios' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `repair_simulation_scenarios` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='report_actions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `report_actions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='report_actions_archive' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `report_actions_archive` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='report_internal_notes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `report_internal_notes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_blocked_times' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_blocked_times` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_business_hours' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_business_hours` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_lines' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_lines` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_menu_lines' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_menu_lines` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_menus' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_menus` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_notification_recipients' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_notification_recipients` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_policies' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_policies` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_recurring_blocked_times' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_recurring_blocked_times` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_reminders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_reminders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_slot_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_slot_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_slots' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_slots` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_team_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_team_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservation_waitlist_entries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservation_waitlist_entries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='reservations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `reservations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resident_activity_snapshots' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `resident_activity_snapshots` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resident_documents' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `resident_documents` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resident_registry' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `resident_registry` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resume_careers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `resume_careers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resume_educations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `resume_educations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resume_qualifications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `resume_qualifications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resume_skills' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `resume_skills` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='resumes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `resumes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='role_permissions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `role_permissions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='roles' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `roles` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='safety_check_message_presets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `safety_check_message_presets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='safety_check_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `safety_check_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='safety_checks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `safety_checks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='safety_response_followups' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `safety_response_followups` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='safety_responses' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `safety_responses` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='saved_segment_presets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `saved_segment_presets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schedule_annual_copy_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `schedule_annual_copy_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schedule_attendance_reminders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `schedule_attendance_reminders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schedule_attendances' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `schedule_attendances` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schedule_cross_refs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `schedule_cross_refs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schedule_delegations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `schedule_delegations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schedule_event_categories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `schedule_event_categories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schedule_media_uploads' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `schedule_media_uploads` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schedule_scheduled_tasks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `schedule_scheduled_tasks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='schedules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `schedules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='seal_scope_defaults' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `seal_scope_defaults` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='seal_stamp_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `seal_stamp_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='search_histories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `search_histories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='search_saved_queries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `search_saved_queries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='security_incidents' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `security_incidents` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='service_record_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `service_record_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='service_record_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `service_record_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='service_record_reactions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `service_record_reactions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='service_record_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `service_record_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='service_record_template_values' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `service_record_template_values` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='service_record_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `service_record_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='service_record_values' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `service_record_values` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='service_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `service_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_facilities' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shared_facilities` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_file_comments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shared_file_comments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_file_links' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shared_file_links` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_file_stars' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shared_file_stars` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_file_tags' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shared_file_tags` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_file_versions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shared_file_versions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_files' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shared_files` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_folders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shared_folders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shedlock' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shedlock` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_assignment_runs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_assignment_runs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_assignments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_assignments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_budget_allocations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_budget_allocations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_budget_consumptions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_budget_consumptions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_budget_failed_events' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_budget_failed_events` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_change_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_change_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_hourly_rates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_hourly_rates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_positions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_positions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_schedules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_schedules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_slots' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_slots` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_swap_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shift_swap_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shopping_list_items' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shopping_list_items` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shopping_lists' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `shopping_lists` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='signage_access_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `signage_access_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='signage_emergency_messages' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `signage_emergency_messages` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='signage_schedules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `signage_schedules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='signage_screens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `signage_screens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='signage_slots' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `signage_slots` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='skill_categories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `skill_categories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='skill_expiry_notifications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `skill_expiry_notifications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sns_feed_configs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `sns_feed_configs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='storage_migration_errors' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `storage_migration_errors` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='storage_plans' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `storage_plans` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='storage_subscriptions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `storage_subscriptions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='storage_usage_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `storage_usage_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stripe_connect_accounts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `stripe_connect_accounts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stripe_customers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `stripe_customers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stripe_webhook_events' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `stripe_webhook_events` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='student_attendance_summaries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `student_attendance_summaries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='succession_covenants' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `succession_covenants` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='succession_pre_registrations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `succession_pre_registrations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='supporter_applications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `supporter_applications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='supporter_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `supporter_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='survey_options' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `survey_options` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='survey_questions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `survey_questions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='survey_responses' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `survey_responses` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='survey_result_viewers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `survey_result_viewers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='survey_targets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `survey_targets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='surveys' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `surveys` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='system_activity_template_presets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `system_activity_template_presets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='system_form_presets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `system_form_presets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='system_onboarding_presets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `system_onboarding_presets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='system_tournament_preset_stat_defs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `system_tournament_preset_stat_defs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='system_tournament_preset_tiebreakers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `system_tournament_preset_tiebreakers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='system_tournament_presets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `system_tournament_presets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tags' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tags` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_access_requirements' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_access_requirements` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_anniversaries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_anniversaries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_blocks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_blocks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_care_notification_overrides' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_care_notification_overrides` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_custom_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_custom_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_enabled_modules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_enabled_modules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_friend_folder_members' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_friend_folder_members` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_friend_folders' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_friend_folders` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_friends' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_friends` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_member_info_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_member_info_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_member_info_responses' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_member_info_responses` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_member_terms' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_member_terms` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_name_disclosure_change_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_name_disclosure_change_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_officers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_officers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_org_memberships' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_org_memberships` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_page_sections' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_page_sections` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_pages' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_pages` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_payment_advances' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_payment_advances` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_presence_icons' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_presence_icons` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_role_aliases' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_role_aliases` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_shift_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_shift_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_slug_history' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_slug_history` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_subscriptions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_subscriptions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='team_uniform_set' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `team_uniform_set` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='teams' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `teams` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='template_modules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `template_modules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='template_wallpapers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `template_wallpapers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ticket_books' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ticket_books` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ticket_consumptions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ticket_consumptions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ticket_payments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ticket_payments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ticket_products' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `ticket_products` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_bookmarks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_bookmarks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_digest_configs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_digest_configs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_digests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_digests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_poll_options' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_poll_options` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_poll_votes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_poll_votes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_polls' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_polls` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_post_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_post_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_post_edits' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_post_edits` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_post_reactions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_post_reactions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_posts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timeline_posts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timetable_changes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timetable_changes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timetable_period_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timetable_period_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timetable_slot_user_note_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timetable_slot_user_note_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timetable_slot_user_note_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timetable_slot_user_note_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timetable_slot_user_notes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timetable_slot_user_notes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timetable_slots' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timetable_slots` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timetable_terms' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timetable_terms` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timetables' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `timetables` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='todo_assignees' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `todo_assignees` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='todo_budget_links' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `todo_budget_links` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='todo_comments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `todo_comments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='todo_handoffs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `todo_handoffs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='todo_personal_memos' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `todo_personal_memos` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='todo_shared_memo_entries' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `todo_shared_memo_entries` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='todo_status_labels' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `todo_status_labels` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='todo_tag_links' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `todo_tag_links` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='todos' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `todos` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_contact_space' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_contact_space` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_divisions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_divisions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_entry_members' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_entry_members` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_entry_template_members' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_entry_template_members` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_entry_template_staff' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_entry_template_staff` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_entry_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_entry_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_fee' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_fee` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_fee_target' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_fee_target` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_individual_rankings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_individual_rankings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_match_player_stats' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_match_player_stats` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_match_rosters' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_match_rosters` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_match_sets' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_match_sets` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_matchdays' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_matchdays` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_matches' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_matches` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_participants' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_participants` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_promotion_records' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_promotion_records` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_scorekeepers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_scorekeepers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_standings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_standings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_stat_defs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_stat_defs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_submission_requirement' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_submission_requirement` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_submission_requirement_target' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_submission_requirement_target` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_template_stat_defs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_template_stat_defs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_template_tiebreakers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_template_tiebreakers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournament_tiebreakers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournament_tiebreakers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tournaments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `tournaments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='translation_assignments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `translation_assignments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='translation_configs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `translation_configs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='two_factor_auth' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `two_factor_auth` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='unseal_audit_views' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `unseal_audit_views` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='unseal_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `unseal_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_action_memo_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_action_memo_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_ad_delivery_counters' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_ad_delivery_counters` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_ad_preferences' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_ad_preferences` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_badges' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_badges` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_blocks' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_blocks` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_blog_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_blog_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_calendar_sync_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_calendar_sync_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_care_links' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_care_links` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_favorites' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_favorites` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_google_calendar_connections' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_google_calendar_connections` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_ical_tokens' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_ical_tokens` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_interest_tags' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_interest_tags` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_line_connections' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_line_connections` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_mutes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_mutes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_nav_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_nav_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_permission_groups' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_permission_groups` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_point_cards' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_point_cards` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_quick_memo_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_quick_memo_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_reflection_settings' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_reflection_settings` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_roles' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_roles` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_schedule_google_events' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_schedule_google_events` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_social_profiles' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_social_profiles` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_village_nicknames' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_village_nicknames` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_village_pins' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_village_pins` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_violations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_violations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_voice_input_consents' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_voice_input_consents` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_weather_locations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `user_weather_locations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `users` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='vendors' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `vendors` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='venues' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `venues` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_calendar_event_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_calendar_event_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_calendar_events' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_calendar_events` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_categories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_categories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_charter_articles' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_charter_articles` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_charter_drafters' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_charter_drafters` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_charter_revisions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_charter_revisions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_charters' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_charters` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_chronicles' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_chronicles` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_creation_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_creation_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_event_archives' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_event_archives` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_festival_live_posts' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_festival_live_posts` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_festival_rsvps' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_festival_rsvps` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_festivals' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_festivals` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_join_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_join_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_lobby_daily_threads' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_lobby_daily_threads` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_match_recruit_applications' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_match_recruit_applications` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_match_recruits' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_match_recruits` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_meetup_attendances' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_meetup_attendances` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_meetup_candidate_dates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_meetup_candidate_dates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_meetup_comments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_meetup_comments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_meetup_todos' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_meetup_todos` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_meetup_votes' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_meetup_votes` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_meetups' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_meetups` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_memberships' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_memberships` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_newsletter_issue_tags' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_newsletter_issue_tags` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_newsletter_issues' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_newsletter_issues` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_newsletter_opt_outs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_newsletter_opt_outs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_newsletter_send_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_newsletter_send_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_newsletter_tags' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_newsletter_tags` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_newsletters' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_newsletters` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_pilgrimage_recommendations' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_pilgrimage_recommendations` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_recruit_categories' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_recruit_categories` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_reports' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_reports` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_representatives' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_representatives` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='village_serendipity_scores' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `village_serendipity_scores` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='villages' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `villages` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='visibility_template_rules' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `visibility_template_rules` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='visibility_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `visibility_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='warning_re_reviews' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `warning_re_reviews` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='weather_location_bootstrap_jobs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `weather_location_bootstrap_jobs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='webauthn_credentials' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `webauthn_credentials` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='webhook_delivery_logs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `webhook_delivery_logs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='webhook_endpoints' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `webhook_endpoints` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='webhook_event_subscriptions' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `webhook_event_subscriptions` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='withdraw_jobs' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `withdraw_jobs` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='workflow_request_approvers' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `workflow_request_approvers` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='workflow_request_attachments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `workflow_request_attachments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='workflow_request_comments' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `workflow_request_comments` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='workflow_request_steps' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `workflow_request_steps` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='workflow_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `workflow_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='workflow_template_fields' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `workflow_template_fields` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='workflow_template_steps' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `workflow_template_steps` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='workflow_templates' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `workflow_templates` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='yabai_unflag_requests' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@c>0, 'ALTER TABLE `yabai_unflag_requests` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET SESSION foreign_key_checks = @prev_fk_checks;

-- =============================================================================
-- 検証: 統一されていない表・列が 1 つでも残っていたら migration を失敗させる。
--
-- 黙って通すと「統一したつもり」の嘘が本番に残るため、必ず落とす。
-- ストアドプロシージャ（SIGNAL）を使えないので、
-- 「違反件数が 0 なら無害な SELECT、0 でなければ存在しない識別子を参照する SQL」を
-- 組み立てて実行する。後者は Unknown column エラーになり、
-- エラーメッセージ自体に違反内容が載るため原因がそのまま読める。
-- =============================================================================

-- 表単位（ビューは TABLE_COLLATION が NULL なので BASE TABLE に限定。
--  flyway_schema_history は Flyway 自身の管理表なので対象外）
SET @bad_tables = (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'
      AND TABLE_NAME<>'flyway_schema_history' AND TABLE_COLLATION<>'utf8mb4_0900_ai_ci');
SET @s = IF(@bad_tables=0, 'SELECT 1',
    CONCAT('SELECT `issue_2589_照合順序の統一に失敗_未統一の表が',
           @bad_tables, '枚残存している` FROM (SELECT 1) AS e'));
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 列単位（表既定だけ揃えても、列に別の照合順序が残っていれば JOIN は同じように落ちる。
--  実際に比較されるのは列なので列そのものを検査する。COLLATION_NAME IS NULL は非文字列列）
SET @bad_cols = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history'
      AND COLLATION_NAME IS NOT NULL AND COLLATION_NAME<>'utf8mb4_0900_ai_ci');
SET @s = IF(@bad_cols=0, 'SELECT 1',
    CONCAT('SELECT `issue_2589_照合順序の統一に失敗_未統一の文字列列が',
           @bad_cols, '本残存している` FROM (SELECT 1) AS e'));
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
