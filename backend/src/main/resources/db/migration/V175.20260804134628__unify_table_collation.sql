-- =============================================================================
-- issue #2589: 照合順序をスキーマ全体で utf8mb4_0900_ai_ci に統一する
-- =============================================================================
--
-- 【根本原因】
-- 本スキーマの照合順序は「表ごとにバラバラ」かつ「一部はサーバ変数 collation_server 依存」だった。
-- db/migration の全 SQL を Flyway 版数順に合成して実測した全 726 表の内訳:
--   * utf8mb4_unicode_ci を明示宣言 ... 551 表（例: notifications / V4.019）
--   * utf8mb4_0900_ai_ci を明示宣言 ...  33 表（村ドメイン一式 / V9.125 以降）
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
--   * 変換方向が「粗い→細かい」になる。utf8mb4_unicode_ci(UCA 4.0.0) は 'ss' 等への展開を
--     等価とみなすが utf8mb4_0900_ai_ci(UCA 9.0.0) は区別する。
--     細かい側へ寄せる変換では等価な値の組が増えないため、
--     既存データが一意制約に新たに違反して migration が失敗することが原理的に起きない
--     （逆向きに unicode_ci へ寄せると等価判定が粗くなり、既存データで一意制約違反を招きうる）。
--
-- 【実装方針: なぜ表名を列挙せず information_schema から動的に決めるのか】
-- 当初は静的解析で得た 726 表を列挙する実装にしたが、実スキーマ上で流したところ
-- 検証ブロックが「未統一の表が 1 枚残存」を検出した。
-- SQL テキストの静的解析には原理的な取りこぼしがあり、列挙は実体と一致しない。
-- そこで変換対象を information_schema（＝実体そのもの）から決める。
-- こうすれば将来 migration が増えて表が変わっても本スクリプトは常に正しく動く。
--
-- ⚠️ 手動適用時の注意: 本スクリプトはストアドプロシージャ（BEGIN ... END）を含む。
--    Flyway の MySQL パーサは BEGIN ... END を自動認識するため DELIMITER 命令は不要だが
--    （V13.045 の CREATE TRIGGER と同じ前提）、素の mysql クライアントから流す場合は
--    DELIMITER の指定が要る（無いと BEGIN 内の ; で文が切れて構文エラーになる）。
--
-- 【冪等性・環境適応】
-- 現在の照合順序が統一先と違う表だけを変換する。
-- そのため本番では約 551 表、ローカルでは約 693 表が実際に変換されるが、
-- スクリプトは同一で結果も同一に収束する。再実行しても対象が 0 件になるだけで安全。
-- =============================================================================

-- データベース既定を固定する。これ以降に作られる表は、
-- サーバ変数 collation_server が何であろうと統一先を継承する（根本原因の除去）。
-- 名前を省略すると既定データベース（＝Flyway の接続先）が対象になる。
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 変換対象を先に確定させる。
-- information_schema を直接カーソルで走査しながら ALTER すると、
-- 走査中に走査対象自身のメタデータが変化して挙動が処理系依存になるため、
-- 対象一覧をいったん実体化してから回す。
DROP TEMPORARY TABLE IF EXISTS tmp_collation_targets_2589;
CREATE TEMPORARY TABLE tmp_collation_targets_2589 (
    seq        INT AUTO_INCREMENT PRIMARY KEY,
    table_name VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_collation_targets_2589 (table_name)
SELECT TABLE_NAME
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_TYPE = 'BASE TABLE'                       -- ビューは TABLE_COLLATION が NULL なので除外
   AND TABLE_NAME <> 'flyway_schema_history'           -- Flyway 自身の管理表は触らない
   AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'         -- 既に統一先の表は対象外（冪等性）
 ORDER BY TABLE_NAME;

DROP PROCEDURE IF EXISTS unify_table_collation_2589;

CREATE PROCEDURE unify_table_collation_2589()
BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_table VARCHAR(64);
    DECLARE cur CURSOR FOR SELECT table_name FROM tmp_collation_targets_2589 ORDER BY seq;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    OPEN cur;
    convert_loop: LOOP
        FETCH cur INTO v_table;
        IF v_done = 1 THEN
            LEAVE convert_loop;
        END IF;

        SET @ddl = CONCAT('ALTER TABLE `', v_table,
                          '` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE cur;
END;

-- 外部キーは参照元・参照先の文字列列の照合順序が一致していないと張れない。
-- 表を 1 枚ずつ変換する過程では両者が一時的に食い違うため、変換中だけ検査を止める。
-- これはエラーの握りつぶしではなく「途中状態を経由するための一時停止」であり、
-- ループ完走後は全表が同一照合順序になるので整合性は回復する
-- （回復していることは下の検証ブロックが機械的に確認し、駄目なら migration ごと失敗させる）。
SET @prev_fk_checks = @@SESSION.foreign_key_checks;
SET SESSION foreign_key_checks = 0;

CALL unify_table_collation_2589();

SET SESSION foreign_key_checks = @prev_fk_checks;

DROP PROCEDURE unify_table_collation_2589;
DROP TEMPORARY TABLE IF EXISTS tmp_collation_targets_2589;

-- =============================================================================
-- 検証: 統一されていない表・列が 1 つでも残っていたら migration を失敗させる。
-- 黙って通すと「統一したつもり」の嘘が本番に残るため、必ず落とす。
-- 違反した対象の名前をエラーメッセージに載せる（件数だけでは原因調査を始められない）。
-- =============================================================================
DROP PROCEDURE IF EXISTS verify_table_collation_2589;

CREATE PROCEDURE verify_table_collation_2589()
BEGIN
    DECLARE v_tables TEXT;
    DECLARE v_columns TEXT;

    SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '(', TABLE_COLLATION, ')') SEPARATOR ', ')
      INTO v_tables
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_TYPE = 'BASE TABLE'
       AND TABLE_NAME <> 'flyway_schema_history'
       AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci';

    IF v_tables IS NOT NULL THEN
        SET @msg = CONCAT('issue #2589: 照合順序の統一に失敗（未統一の表）: ', LEFT(v_tables, 400));
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @msg;
    END IF;

    -- 表既定だけ揃えても列に別の照合順序が残っていれば JOIN は同じように落ちる。
    -- 実際に比較されるのは列なので列そのものを検査する（COLLATION_NAME IS NULL は非文字列列）。
    SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '.', COLUMN_NAME, '(', COLLATION_NAME, ')') SEPARATOR ', ')
      INTO v_columns
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME <> 'flyway_schema_history'
       AND COLLATION_NAME IS NOT NULL
       AND COLLATION_NAME <> 'utf8mb4_0900_ai_ci';

    IF v_columns IS NOT NULL THEN
        SET @msg = CONCAT('issue #2589: 照合順序の統一に失敗（未統一の文字列列）: ', LEFT(v_columns, 400));
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @msg;
    END IF;
END;

CALL verify_table_collation_2589();

DROP PROCEDURE verify_table_collation_2589;
