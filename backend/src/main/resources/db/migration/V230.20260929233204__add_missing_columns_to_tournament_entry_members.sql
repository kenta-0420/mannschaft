-- CMP-260924-0010 U2: 大会エントリーメンバー系の欠落列を追加する（凍結台帳 KNOWN_UNPAID_DRIFT の返済）。
--
-- Entity にだけ列を足して migration を書き忘れていた（queue_tickets.guest_phone と同型）。
-- Flyway で構築した環境では Hibernate が存在しない列を含む SQL を発行し Unknown column で失敗していた。
--   - tournament_entry_members.member_number          … TournamentEntryMemberEntity: @Column(length = 50) String（NULL 可）
--   - tournament_entry_template_members.created_at     … TournamentEntryTemplateMemberEntity: @PrePersist で必ず書き込む
--   - tournament_entry_template_members.updated_at     … 同 @PrePersist / @PreUpdate で必ず書き込む
-- 日時 2 列は既存慣行（V9.122 の tournament_entry_members）に揃え NOT NULL + DEFAULT とする。
-- 既存行には元になる日時列が無いため、DEFAULT（migration 実行時刻）のまま埋め戻さない。
--
-- なお両テーブルの id / template_id（DDL CHAR(36) vs Entity UUID BINARY(16)）・position 長・notes 型の
-- 型ずれは本 migration の対象外（CMP-260929-0654 で扱う）。
--
-- 冪等: 列ごとに information_schema で存在を確かめてから足す（手本は V18.030）。
-- 注意: 番人（FlywayFromScratchMigrationTest の UnpaidDriftRepaymentFixture）が本ファイルを ; で分割して再実行するため、
-- 文字列リテラル内に ; を書かないこと。

SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_members' AND COLUMN_NAME = 'member_number');
SET @s1 = IF(@c1 = 0, 'ALTER TABLE tournament_entry_members ADD COLUMN member_number VARCHAR(50) NULL COMMENT ''チームメンバー番号'' AFTER user_id', 'SELECT 1');
PREPARE stmt FROM @s1; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c2 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'created_at');
SET @s2 = IF(@c2 = 0, 'ALTER TABLE tournament_entry_template_members ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP', 'SELECT 1');
PREPARE stmt FROM @s2; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c3 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'updated_at');
SET @s3 = IF(@c3 = 0, 'ALTER TABLE tournament_entry_template_members ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP', 'SELECT 1');
PREPARE stmt FROM @s3; EXECUTE stmt; DEALLOCATE PREPARE stmt;
