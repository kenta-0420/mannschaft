-- CMP-260929-0654: 大会エントリー系・興味タグの主キー／外部キー列を CHAR(36) から BINARY(16) へ移行する。
--
-- 背景: 下記 5 表 6 列は DDL が CHAR(36) のまま、Entity は UuidV7Entity 系（Hibernate 標準の BINARY 表現＝16 バイト）で
--       宣言されており、Flyway で構築した環境では保存・取得のたびに Incorrect string value で失敗していた。
--       application-test.yml の ddl-auto:create が Entity から DDL を生成するため、通常の IT では原理的に検出できなかった。
--   - tournament_entry_members.id               （V9.122）
--   - tournament_entry_templates.id             （V9.123）
--   - tournament_entry_template_members.id      （V9.124）
--   - tournament_entry_template_members.template_id（V9.124）
--   - tournament_entry_template_staff.template_id（V9.20260603000005。staff.id は既に BINARY(16) のため変更しない）
--   - user_interest_tags.id                     （V68.003）
--
-- 方針: 既存行は UUID_TO_BIN(x)（swap_flag なし）で値を保持する。これは Hibernate が UUID を BINARY(16) へ書く
--       標準の表現（上位 64 ビット→下位 64 ビットの順）と一致する。
--
-- 順序:
--   1. 外部キー（fk_tetm_template / fk_template_staff_template）を削除
--   2. NULL 可の一時列（*_bin）を追加
--   3. UUID_TO_BIN で変換
--   4. 変換結果を検証（NULL 件数と HEX 照合。不一致があれば存在しない表を DROP して意図的に失敗させ、旧列を消さずに止める）
--   5. 依存する索引と主キーを旧列ごと落とし、一時列を元の名前へ改名し、索引と主キーを再作成（表ごとに 1 本の ALTER で原子的）
--   6. 親（tournament_entry_templates）の主キーが BINARY(16) になってから外部キーを再作成
--
-- 冪等: 手順ごとに information_schema で状態を判定してから実行する（手本は V230.20260929233204）。
--       どの手順で中断しても、再実行で最後まで完遂する。
--
-- 注意:
--   - 番人テスト（TournamentEntryPkTypeMigrationIT）が本ファイルをセミコロンで分割して途中状態を再現するため、
--     文字列リテラル内にセミコロンを書かないこと。
--   - MySQL は隣接する文字列リテラルを連結しないため、COMMENT などを改行で分割しないこと。
--   - 照合順序は変えない（BINARY 列は照合順序を持たない。他の列・表の照合順序には触れない）。

-- ---------------------------------------------------------------------------
-- 1. 外部キーの削除（子の template_id がまだ CHAR(36) のときだけ）
-- ---------------------------------------------------------------------------
SET @s = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND CONSTRAINT_NAME = 'fk_tetm_template' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1, 'ALTER TABLE tournament_entry_template_members DROP FOREIGN KEY fk_tetm_template', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND CONSTRAINT_NAME = 'fk_template_staff_template' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1, 'ALTER TABLE tournament_entry_template_staff DROP FOREIGN KEY fk_template_staff_template', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2-4. 一時列の追加・変換・検証
-- ---------------------------------------------------------------------------
-- tournament_entry_members.id
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_members' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_members' AND COLUMN_NAME = 'id_bin') = 0, 'ALTER TABLE tournament_entry_members ADD COLUMN id_bin BINARY(16) NULL AFTER id', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_members' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_members' AND COLUMN_NAME = 'id_bin') = 1, 'UPDATE tournament_entry_members SET id_bin = UUID_TO_BIN(id) WHERE id_bin IS NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @bad = 0;
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_members' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_members' AND COLUMN_NAME = 'id_bin') = 1, 'SELECT COUNT(*) INTO @bad FROM tournament_entry_members WHERE id_bin IS NULL OR HEX(id_bin) <> UPPER(REPLACE(id, ''-'', ''''))', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(@bad > 0, 'DROP TABLE `V232_verify_failed_tem_id`', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- tournament_entry_templates.id
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id_bin') = 0, 'ALTER TABLE tournament_entry_templates ADD COLUMN id_bin BINARY(16) NULL AFTER id', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id_bin') = 1, 'UPDATE tournament_entry_templates SET id_bin = UUID_TO_BIN(id) WHERE id_bin IS NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @bad = 0;
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id_bin') = 1, 'SELECT COUNT(*) INTO @bad FROM tournament_entry_templates WHERE id_bin IS NULL OR HEX(id_bin) <> UPPER(REPLACE(id, ''-'', ''''))', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(@bad > 0, 'DROP TABLE `V232_verify_failed_tet_id`', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- tournament_entry_template_members.id / template_id
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'id_bin') = 0, 'ALTER TABLE tournament_entry_template_members ADD COLUMN id_bin BINARY(16) NULL AFTER id', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id_bin') = 0, 'ALTER TABLE tournament_entry_template_members ADD COLUMN template_id_bin BINARY(16) NULL AFTER template_id', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'id_bin') = 1, 'UPDATE tournament_entry_template_members SET id_bin = UUID_TO_BIN(id) WHERE id_bin IS NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id_bin') = 1, 'UPDATE tournament_entry_template_members SET template_id_bin = UUID_TO_BIN(template_id) WHERE template_id_bin IS NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @bad = 0;
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'id_bin') = 1, 'SELECT COUNT(*) INTO @bad FROM tournament_entry_template_members WHERE id_bin IS NULL OR HEX(id_bin) <> UPPER(REPLACE(id, ''-'', ''''))', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(@bad > 0, 'DROP TABLE `V232_verify_failed_tetm_id`', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @bad = 0;
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id_bin') = 1, 'SELECT COUNT(*) INTO @bad FROM tournament_entry_template_members WHERE template_id_bin IS NULL OR HEX(template_id_bin) <> UPPER(REPLACE(template_id, ''-'', ''''))', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(@bad > 0, 'DROP TABLE `V232_verify_failed_tetm_template_id`', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- tournament_entry_template_staff.template_id
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id_bin') = 0, 'ALTER TABLE tournament_entry_template_staff ADD COLUMN template_id_bin BINARY(16) NULL AFTER template_id', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id_bin') = 1, 'UPDATE tournament_entry_template_staff SET template_id_bin = UUID_TO_BIN(template_id) WHERE template_id_bin IS NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @bad = 0;
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id_bin') = 1, 'SELECT COUNT(*) INTO @bad FROM tournament_entry_template_staff WHERE template_id_bin IS NULL OR HEX(template_id_bin) <> UPPER(REPLACE(template_id, ''-'', ''''))', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(@bad > 0, 'DROP TABLE `V232_verify_failed_tets_template_id`', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- user_interest_tags.id
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_interest_tags' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_interest_tags' AND COLUMN_NAME = 'id_bin') = 0, 'ALTER TABLE user_interest_tags ADD COLUMN id_bin BINARY(16) NULL AFTER id', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_interest_tags' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_interest_tags' AND COLUMN_NAME = 'id_bin') = 1, 'UPDATE user_interest_tags SET id_bin = UUID_TO_BIN(id) WHERE id_bin IS NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @bad = 0;
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_interest_tags' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_interest_tags' AND COLUMN_NAME = 'id_bin') = 1, 'SELECT COUNT(*) INTO @bad FROM user_interest_tags WHERE id_bin IS NULL OR HEX(id_bin) <> UPPER(REPLACE(id, ''-'', ''''))', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(@bad > 0, 'DROP TABLE `V232_verify_failed_uit_id`', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 5. 主キー・依存索引の付け替え（表ごとに 1 本の ALTER）
-- ---------------------------------------------------------------------------
-- tournament_entry_members（id は主キーのみ。uq_tem_participant_user / idx_tem_participant / idx_tem_user は id を含まないため触らない）
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_members' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_members' AND COLUMN_NAME = 'id_bin') = 1, 'ALTER TABLE tournament_entry_members DROP PRIMARY KEY, DROP COLUMN id, CHANGE COLUMN id_bin id BINARY(16) NOT NULL FIRST, ADD PRIMARY KEY (id)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- tournament_entry_templates（idx_tet_team は id を含まない）
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id_bin') = 1, 'ALTER TABLE tournament_entry_templates DROP PRIMARY KEY, DROP COLUMN id, CHANGE COLUMN id_bin id BINARY(16) NOT NULL FIRST, ADD PRIMARY KEY (id)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- tournament_entry_template_members（uq_tetm_template_user / idx_tetm_template は template_id を含むため作り直す。idx_tetm_user は触らない）
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'id_bin') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id_bin') = 1, 'ALTER TABLE tournament_entry_template_members DROP PRIMARY KEY, DROP INDEX uq_tetm_template_user, DROP INDEX idx_tetm_template, DROP COLUMN id, DROP COLUMN template_id, CHANGE COLUMN id_bin id BINARY(16) NOT NULL FIRST, CHANGE COLUMN template_id_bin template_id BINARY(16) NOT NULL AFTER id, ADD PRIMARY KEY (id), ADD UNIQUE INDEX uq_tetm_template_user (template_id, user_id), ADD INDEX idx_tetm_template (template_id, sort_order)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- tournament_entry_template_staff（idx_template_staff_template は template_id を含むため作り直す）
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id_bin') = 1, 'ALTER TABLE tournament_entry_template_staff DROP INDEX idx_template_staff_template, DROP COLUMN template_id, CHANGE COLUMN template_id_bin template_id BINARY(16) NOT NULL COMMENT ''親 tournament_entry_templates.id（BINARY(16)）'' AFTER id, ADD INDEX idx_template_staff_template (template_id, sort_order)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- user_interest_tags（id は主キーのみ。uq_user_interest_tag / idx_uit_tag_hash / idx_uit_user_id は id を含まない）
SET @s = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_interest_tags' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'char') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_interest_tags' AND COLUMN_NAME = 'id_bin') = 1, 'ALTER TABLE user_interest_tags DROP PRIMARY KEY, DROP COLUMN id, CHANGE COLUMN id_bin id BINARY(16) NOT NULL FIRST, ADD PRIMARY KEY (id)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 6. 外部キーの再作成（親の主キーが BINARY(16) になってから。同一ドメイン内のため CASCADE 可）
-- ---------------------------------------------------------------------------
SET @s = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND CONSTRAINT_NAME = 'fk_tetm_template' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_members' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'binary') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'binary') = 1, 'ALTER TABLE tournament_entry_template_members ADD CONSTRAINT fk_tetm_template FOREIGN KEY (template_id) REFERENCES tournament_entry_templates (id) ON DELETE CASCADE', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND CONSTRAINT_NAME = 'fk_template_staff_template' AND CONSTRAINT_TYPE = 'FOREIGN KEY') = 0 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_template_staff' AND COLUMN_NAME = 'template_id' AND DATA_TYPE = 'binary') = 1 AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament_entry_templates' AND COLUMN_NAME = 'id' AND DATA_TYPE = 'binary') = 1, 'ALTER TABLE tournament_entry_template_staff ADD CONSTRAINT fk_template_staff_template FOREIGN KEY (template_id) REFERENCES tournament_entry_templates (id) ON DELETE CASCADE', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

