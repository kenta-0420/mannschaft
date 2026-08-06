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
--
-- =============================================================================
-- ⚠️【重要】本変換は一意制約違反を起こしうる（実測に基づく事実）
-- =============================================================================
-- 本 PR の初版はここに「変換方向は粗い→細かいなので等価な値は増えず、
-- 一意制約違反は原理的に起きない」と書いていた。これは **誤りである**。撤回する。
--
-- MySQL 8.0 上で 65,502 個のコードポイントについて WEIGHT_STRING() を
-- 両照合順序で比較した実測結果:
--   * utf8mb4_0900_ai_ci で新たに「等価」になる文字グループ … 666 グループ（＝危険側）
--   * utf8mb4_0900_ai_ci で新たに「区別」される文字グループ …  44 グループ（＝安全側）
--
-- 危険側の代表例（ペア単位でも検算済み。unicode_ci では区別され 0900_ai_ci では同一になる）:
--   * 異なる字体系のゼロ : ASCII '0'(U+0030) と NKo(U+07C0) / タミル(U+0BE6) など 44 文字
--   * 同様に '1' と 50 文字、'2' と 49 文字 …（各種スクリプトの数字）
--   * 縦書き用の異体 : ','(U+002C) と縦書きカンマ(U+FE10)
--   * 無視可能文字（制御文字等）が 729 文字まとめて 1 グループ
--
-- ⚠️ 直感に反するが、以下は **どちらの照合順序でも既に等価** であり危険軸ではない（実測）:
--   * 全角/半角の素の形 : ','と'，' / 'A'と'Ａ' / '。'と'｡' / ','と小字形'﹐'
--   * アクセント : 'e' と 'é'（utf8mb4_unicode_ci も accent insensitive であるため）
--   * 濁点・半濁点 : 'は' と 'ば' / 'か' と 'が'
--   * かな種別 : 'は' と 'ハ' / 'あ' と 'ア'
-- ⇒ 「全角半角や濁点が危ない」という直感で判断してはならない。危険なのは上記の限定的な文字である。
--
-- ⇒ UNIQUE 制約の張られた文字列列に、変換後に等価となるペアが既存データに 1 組でもあれば
--   ERROR 1062 Duplicate entry で ALTER が失敗する。
--   MySQL の DDL は暗黙コミットのため、ループ途中で落ちると
--   「一部の表だけ新照合順序」という混在状態が残り、現状より状況が悪化する。
--   逆方向 migration も同じ規模の再構築であり、巻き戻しは実質不可能である。
--
-- ⇒ そこで本スクリプトは **1 表も変換しないうちに** 全 UNIQUE 制約を実データで事前検査し、
--   衝突が 1 件でもあれば何も変更せずに中断する（STEP 1）。
--   安全性の根拠は「照合順序の理屈」ではなく「実データに対する事前検査」に置く。
--
-- 失敗時の対応手順は docs/architecture/collation_unification_runbook.md を参照。
--
-- 【実装方針: なぜ表名を列挙せず information_schema から動的に決めるのか】
-- 当初は静的解析で得た 726 表を列挙する実装にしたが、実 Flyway スキーマ上で流したところ
-- 検証ブロックが「未統一の表が 1 枚残存」を検出した。
-- SQL テキストの静的解析には原理的な取りこぼしがあり、列挙は実体と一致しない。
-- そこで変換対象・検査対象はすべて information_schema（＝実体そのもの）から導出する。
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

-- GROUP_CONCAT の既定上限は 1024 バイトで、超えると**警告のみで黙って切り捨てられる**。
-- 本スクリプトは GROUP_CONCAT で GROUP BY 式を組み立てるため、切り捨てられると
-- 「列の一部だけで一意性を検査する」誤った SQL が出来上がり、検査をすり抜ける。
-- 列数の多い複合 UNIQUE でも切れないよう十分な値に引き上げる。
SET SESSION group_concat_max_len = 1048576;

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

-- 事前検査で見つけた衝突を貯める（1 件目で止めず全件報告してから中断するため）。
--
-- ⚠️ TEMPORARY ではなく**永続テーブル**にしてある。
--    SIGNAL の MESSAGE_TEXT は MySQL の仕様で 128 文字までしか入らず、
--    索引名・列名・衝突値といった対処に必要な詳細はそこに収まらない。
--    詳細をメッセージに詰め込んで切り詰めるのは情報の消失であり、
--    「詳細を見て対処せよ」と指示している runbook と噛み合わない。
--    そこで詳細は本テーブルに残し、MESSAGE_TEXT には件数と参照先だけを入れる。
--    中断せず正常終了した場合は不要なので末尾で DROP し、スキーマを汚さない。
DROP TABLE IF EXISTS collation_precheck_findings;
CREATE TABLE collation_precheck_findings (
    seq          INT AUTO_INCREMENT PRIMARY KEY,
    table_name   VARCHAR(64)  NOT NULL,
    index_name   VARCHAR(64)  NOT NULL,
    column_list  VARCHAR(512) NOT NULL,
    sample_value VARCHAR(512) NULL,
    -- 重複グループ数。UNCHECKABLE のときは「計算に到達していない」ので NULL を入れる。
    -- ここに 0 を入れると「重複ゼロだから安全」と誤読され、
    -- 検査できていないまま安全と判断される最悪の結末を招く。NULL と 0 は必ず区別する。
    dup_groups   BIGINT       NULL,
    -- DUPLICATE  … 変換すると一意制約に違反する組が実在する
    -- UNCHECKABLE… 検査用 SQL 自体が実行できず、衝突の有無を判定できなかった
    --              （黙って見逃さず、判定不能も中断理由として扱う）
    reason       VARCHAR(16)  NOT NULL DEFAULT 'DUPLICATE',
    -- UNCHECKABLE のとき、なぜ検査できなかったかを MySQL のエラー番号とメッセージで残す。
    -- 「判定不能」というラベルだけでは ERROR 1253（非文字列への COLLATE）なのか
    -- 別の原因なのかが区別できず、原因究明がここで止まってしまうため。
    err_code     INT          NULL,
    err_message  VARCHAR(512) NULL,
    check_sql    TEXT         NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- =============================================================================
-- 【教訓】診断の仕組みを足すたびに、その仕組み自体が新しい嘘をついた
--
-- この事前ゲートを動かすまでに、原因が 5 層に重なっていた。
-- 各層は「前の層を直して初めて見える」ようになっており、
-- **見えているエラーが真因とは限らない**ばかりか、
-- **エラーが無いこと自体が異常のサインでありうる**ことを示している。
--
--   1層目 errno=1253  … 非文字列を返す式に COLLATE を付けていた（真の情報）
--                        → CAST((expr) AS CHAR) COLLATE ... に是正
--   2層目 errno=1243  … PREPARE 失敗後も CONTINUE ハンドラで EXECUTE/DEALLOCATE が走り、
--                        後始末のエラーが真の errno を上書きしていた（自作の目隠し）
--                        → 失敗したら後続の文を実行しない。初回の errno を保持する
--   3層目 errno=NULL  … `IF @err_code IS NULL THEN GET DIAGNOSTICS ...` と書いたため、
--                        IF の条件評価が診断領域をクリアしていた（自作の目隠し）
--                        → GET DIAGNOSTICS をハンドラの第一文にする
--   4層目 errno=NULL  … ハンドラ本体の DECLARE ... DEFAULT がブロック入場時に代入として走り、
--                        やはり診断領域をクリアしていた（自作の目隠し）
--                        → ローカル変数をやめ、セッション変数へ直接採取する
--   5層目 errno=1064  … ようやく現れた真因。information_schema.STATISTICS.EXPRESSION は
--                        クォートをバックスラッシュでエスケープした文字列を返すため
--                        （例: _utf8mb4\'9999-12-31 00:00:00\'）、そのまま動的 SQL に
--                        埋め込むと構文エラーになる
--                        → REPLACE で \' を ' に戻してから埋め込む
--
-- ⇒ **診断コードを足したら、その診断コードが正しく動くことを別途確かめること。**
--    本件では最小再現（わざと失敗する PREPARE だけのプロシージャ）を作って
--    採取機構の正常性を先に確認し、そこで 3〜4 層目を切り分けた。
-- =============================================================================

-- 検査 SQL を実行し、成功可否を返す。
-- 失敗を握りつぶすためではなく、「判定できなかった」ことを呼び出し側に伝えて
-- 中断理由に積み上げるためのラッパである（例外を無視して先へ進むことはしない）。
DROP PROCEDURE IF EXISTS try_count_dups_2589;

CREATE PROCEDURE try_count_dups_2589(IN p_sql TEXT, OUT p_ok TINYINT)
BEGIN
    DECLARE v_failed TINYINT DEFAULT 0;

    -- 失敗したときは「なぜ失敗したか」を必ず捕まえる。
    -- ラベルだけ残して原因を捨てると、運用者も開発者もここで調査が止まる。
    --
    -- ⚠️ CONTINUE ハンドラは「失敗した文の次から実行を継続する」。
    --    素直に PREPARE / EXECUTE / DEALLOCATE を並べると、PREPARE が失敗した場合に
    --    EXECUTE と DEALLOCATE も «Unknown prepared statement handler» で失敗し、
    --    真の原因が後始末のエラー（errno 1243）で上書きされて消える。
    --    実際にこれで 1243 だけが記録され、原因究明が止まった。
    --    そこで (1) 最初に捕まえたエラーだけを保持し、(2) 失敗後は後続の文を実行しない。
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION
    BEGIN
        -- ⚠️⚠️ GET DIAGNOSTICS は「ハンドラ本体の正真正銘の第一文」でなければならない。
        --    診断領域は多くの文の実行でクリアされるが、その「文」には
        --    **ローカル変数の DECLARE ... DEFAULT も含まれる**（ブロック入場時に代入として走るため）。
        --    したがってハンドラ本体に DECLARE を置いた時点で診断領域は失われる。
        --    最小再現で実測済み:
        --      A) ハンドラ内に DECLARE ... DEFAULT あり → errno = NULL（採取できない）
        --      B) DECLARE 無し・GET DIAGNOSTICS が第一文 → errno = 1064（採取できる）
        --    そのためローカル変数を使わず、セッション変数へ直接採取する（宣言が不要になる）。
        --    採取（無条件・最初）と、初回値を優先する選別（その後）は必ず分けること。
        GET DIAGNOSTICS CONDITION 1 @c_errno = MYSQL_ERRNO, @c_msg = MESSAGE_TEXT;

        IF @err_code IS NULL THEN
            SET @err_code = @c_errno;
            SET @err_message = @c_msg;
        END IF;
        SET v_failed = 1;
    END;

    SET p_ok = 1;
    SET @err_code = NULL;
    SET @err_message = NULL;
    SET @dup_groups = NULL;
    SET @inner_sql = p_sql;
    -- どこまで到達したかを残す。エラーが出ていないのに結果が無い場合、
    -- 「どの文まで実行されたか」が分からないと原因を推測でしか語れなくなる。
    SET @dbg_prepared = 0;
    SET @dbg_executed = 0;
    SET @dbg_after = 'unset';

    PREPARE stmt FROM @inner_sql;

    -- PREPARE が成功したときだけ EXECUTE / DEALLOCATE へ進む。
    -- EXECUTE が失敗しても DEALLOCATE は必要（ハンドルは確保済みのため）なので
    -- この IF の中で両方を実行する。
    IF v_failed = 0 THEN
        SET @dbg_prepared = 1;
        EXECUTE stmt;
        SET @dbg_executed = IF(v_failed = 0, 1, 0);
        SET @dbg_after = CONCAT('dup=', IFNULL(CAST(@dup_groups AS CHAR), 'NULL'));
        DEALLOCATE PREPARE stmt;
    END IF;

    IF v_failed = 1 THEN
        SET p_ok = 0;
        -- 「ハンドラは発火したのに errno が採れていない」は、診断機構そのものの故障である。
        -- これを静かに通すと「例外なし」という嘘が記録され、無関係な場所を掘ることになる
        -- （実際に一度そうなった）。矛盾は矛盾として明示的に記録する。
        IF @err_code IS NULL THEN
            SET @err_message = CONCAT(
                '診断採取に失敗: SQLEXCEPTION ハンドラは発火したが errno を採取できなかった。',
                'GET DIAGNOSTICS がハンドラの先頭で実行されているか確認すること');
        END IF;
    END IF;
END;

-- =============================================================================
-- STEP 1: 事前検査 — 変換で一意制約違反が起きないかを実データで確認する
--
-- 対象は「変換される表」の UNIQUE インデックスのうち文字列列を含むもの。
-- 単一列だけでなく複合 UNIQUE も対象にする（複合の一部の列だけが文字列でも、
-- その列の等価判定が変われば組全体の一意性が変わるため）。
--
-- 検査は GROUP BY ... COLLATE utf8mb4_0900_ai_ci で行う。
-- 現行の照合順序では一意（UNIQUE 制約が通っている）のだから、
-- 変換後の照合順序でグループ化して 2 件以上になる組は
-- そのまま「変換によって新たに生じる重複」である。
--
-- 注意点:
--   * MySQL の UNIQUE は NULL を含む行を重複扱いしないため、いずれかの列が NULL の行は除外する
--   * プレフィックスインデックス（col(191)）は LEFT(col, 191) で同じ土俵に載せる
--   * 非文字列列は COLLATE を付けずそのままグループ化キーにする
--
-- ⚠️【sql_mode = ONLY_FULL_GROUP_BY を前提にすること】
--   MySQL 8.0 の既定 sql_mode には ONLY_FULL_GROUP_BY が含まれ、本番 RDS もこの既定で動く。
--   このモードでは「GROUP BY に無い非集約列」を SELECT できない。
--   本検査は `col COLLATE utf8mb4_0900_ai_ci` でグループ化するため、
--   素の `col` は「グループ化列と関数従属でない」と判定され、報告用にそのまま SELECT すると
--     ERROR 1055: Expression #1 of SELECT list is not in GROUP BY clause ...
--   で落ちる。**守るはずの事前検査自身が本番で最初に壊れる**という事故になる。
--   （実際 CI の shard 4 でこれを踏んだ。データの無いテストスキーマでは
--     衝突 0 件で分岐に入らず露見しないため、発火実証テストを別途置いている）
--   したがって報告用の値は必ず ANY_VALUE() で集約する。
--   sql_mode を緩める回避（SET SESSION sql_mode='' 等）は対処療法であり禁止。
--   本番の既定 sql_mode のまま通る SQL を書くこと。
-- =============================================================================
DROP PROCEDURE IF EXISTS precheck_collation_conflicts_2589;

CREATE PROCEDURE precheck_collation_conflicts_2589()
BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_table VARCHAR(64);
    DECLARE v_index VARCHAR(64);
    DECLARE v_group_expr   TEXT;
    DECLARE v_display_expr TEXT;
    DECLARE v_notnull_expr TEXT;
    DECLARE v_columns      TEXT;
    DECLARE v_dups BIGINT DEFAULT 0;

    -- 変換対象表の UNIQUE インデックスを、インデックス単位で 1 行に畳んで取り出す。
    -- 畳む際に GROUP BY 式 / 表示式 / NOT NULL 条件を SEQ_IN_INDEX 順で組み立てる。
    DECLARE cur CURSOR FOR
        SELECT s.TABLE_NAME,
               s.INDEX_NAME,
               GROUP_CONCAT(
                   CASE WHEN s.EXPRESSION IS NOT NULL
                        -- 関数キーパート（MySQL 8.0 の関数インデックス）。
                        -- COLUMN_NAME は NULL で、実体の隠し仮想生成列は COLUMNS に現れない。
                        -- 式そのものをグループ化キーにする（これを落とすと式が粗くなり誤検知する）。
                        --
                        -- ⚠️ 式の結果型は information_schema からは分からない。
                        --    素の式に COLLATE を付けると、数値・日時を返す式に対して
                        --    ERROR 1253 (COLLATION ... is not valid for CHARACTER SET ...) になる
                        --    （実在例: shift_budget_allocations.uq_sba_scope_category_period の
                        --      coalesce(team_id,0) / coalesce(deleted_at,'9999-12-31 00:00:00')）。
                        --    そこで CAST(... AS CHAR) で文字列に寄せてから COLLATE を当てる。
                        --    数値・日時は文字列化しても正準表現が 1 対 1 に対応するので
                        --    グループ化の意味論は保たれ、かつ照合順序に非依存になる。
                        --    型判定を information_schema に頼らずに済むのが利点。
                        THEN CONCAT('CAST((', REPLACE(s.EXPRESSION, CONCAT(CHAR(92), CHAR(39)), CHAR(39)), ') AS CHAR) COLLATE utf8mb4_0900_ai_ci')
                        WHEN c.COLLATION_NAME IS NULL
                        -- 非文字列列（数値・日時）とバイナリ列（BLOB/VARBINARY）。
                        -- どちらも照合順序に非依存なので素のまま使う。
                        -- バイナリ列は CAST(... AS CHAR) すると不正なバイト列になりうるため
                        -- 決して文字列化しないこと。
                        THEN CONCAT('`', s.COLUMN_NAME, '`')
                        WHEN s.SUB_PART IS NULL
                        THEN CONCAT('`', s.COLUMN_NAME, '` COLLATE utf8mb4_0900_ai_ci')
                        ELSE CONCAT('LEFT(`', s.COLUMN_NAME, '`, ', s.SUB_PART,
                                    ') COLLATE utf8mb4_0900_ai_ci')
                   END
                   ORDER BY s.SEQ_IN_INDEX SEPARATOR ', '),
               -- 報告用の表示式。ANY_VALUE() で包むのは必須である（下記 sql_mode の注意を参照）。
               GROUP_CONCAT(
                   CASE WHEN s.EXPRESSION IS NOT NULL
                        THEN CONCAT('ANY_VALUE(COALESCE(CAST((', REPLACE(s.EXPRESSION, CONCAT(CHAR(92), CHAR(39)), CHAR(39)),
                                    ') AS CHAR), ''<NULL>''))')
                        ELSE CONCAT('ANY_VALUE(COALESCE(CAST(`', s.COLUMN_NAME,
                                    '` AS CHAR), ''<NULL>''))')
                   END
                   ORDER BY s.SEQ_IN_INDEX SEPARATOR ', ''|'', '),
               GROUP_CONCAT(
                   CASE WHEN s.EXPRESSION IS NOT NULL
                        THEN CONCAT('(', REPLACE(s.EXPRESSION, CONCAT(CHAR(92), CHAR(39)), CHAR(39)), ') IS NOT NULL')
                        ELSE CONCAT('`', s.COLUMN_NAME, '` IS NOT NULL')
                   END
                   ORDER BY s.SEQ_IN_INDEX SEPARATOR ' AND '),
               GROUP_CONCAT(COALESCE(s.COLUMN_NAME, CONCAT('式:', LEFT(s.EXPRESSION, 60)))
                   ORDER BY s.SEQ_IN_INDEX SEPARATOR ',')
          FROM information_schema.STATISTICS s
          -- ⚠️ LEFT JOIN であること。関数キーパートは COLUMN_NAME が NULL で
          --    対応する行が COLUMNS に存在しないため、INNER JOIN にすると
          --    そのキーパートだけが黙って脱落し、本来より粗いグループ化式が組まれる。
          --    粗い式は「照合順序と無関係な重複」を拾って誤検知し、
          --    かつ式に含まれる文字列列の真の衝突を見逃す（偽陽性と偽陰性が同居する）。
          LEFT JOIN information_schema.COLUMNS c
            ON c.TABLE_SCHEMA = s.TABLE_SCHEMA
           AND c.TABLE_NAME   = s.TABLE_NAME
           AND c.COLUMN_NAME  = s.COLUMN_NAME
          JOIN tmp_collation_targets_2589 t
            ON t.table_name = s.TABLE_NAME
         WHERE s.TABLE_SCHEMA = DATABASE()
           AND s.NON_UNIQUE = 0                      -- UNIQUE / PRIMARY のみ
           AND s.INDEX_TYPE <> 'FULLTEXT'
         GROUP BY s.TABLE_NAME, s.INDEX_NAME
        -- 文字列列を含むものに加え、関数キーパートを持つ索引も検査対象にする
        -- （式が文字列を返す場合は照合順序の影響を受けるため）。
        HAVING SUM(c.COLLATION_NAME IS NOT NULL) > 0
            OR SUM(s.EXPRESSION IS NOT NULL) > 0
         ORDER BY s.TABLE_NAME, s.INDEX_NAME;

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    OPEN cur;
    precheck_loop: LOOP
        FETCH cur INTO v_table, v_index, v_group_expr, v_display_expr, v_notnull_expr, v_columns;
        IF v_done = 1 THEN
            LEAVE precheck_loop;
        END IF;

        -- 変換後の照合順序で重複するグループ数を数える
        SET @sql = CONCAT(
            'SELECT COUNT(*) INTO @dup_groups FROM (SELECT 1 FROM `', v_table,
            '` WHERE ', v_notnull_expr,
            ' GROUP BY ', v_group_expr,
            ' HAVING COUNT(*) > 1) AS g');
        CALL try_count_dups_2589(@sql, @exec_ok);

        IF @exec_ok = 0 OR @dup_groups IS NULL THEN
            -- 検査できなかった索引は「衝突なし」とみなさない。
            -- 判定不能のまま変換に進むと、本番で ERROR 1062 に化けて途中停止する。
            INSERT INTO collation_precheck_findings
                (table_name, index_name, column_list, sample_value, dup_groups, reason,
                 err_code, err_message, check_sql)
            VALUES (v_table, v_index, LEFT(v_columns, 512),
                    NULL, NULL, 'UNCHECKABLE',
                    @err_code,
                    LEFT(CONCAT(IFNULL(@err_message, '(例外なし)'),
                                ' | 到達状況: prepared=', IFNULL(@dbg_prepared, '?'),
                                ' executed=', IFNULL(@dbg_executed, '?'),
                                ' EXECUTE直後の', IFNULL(@dbg_after, '?'),
                                ' / 呼び出し元での@dup_groups=',
                                IFNULL(CAST(@dup_groups AS CHAR), 'NULL'),
                                ' @exec_ok=', IFNULL(CAST(@exec_ok AS CHAR), 'NULL')), 512),
                    @sql);
            ITERATE precheck_loop;
        END IF;

        SET v_dups = @dup_groups;

        IF v_dups > 0 THEN
            -- 調査を始められるよう、衝突している実際の値を 1 例だけ拾う
            SET @sql = CONCAT(
                'SELECT CONCAT(', v_display_expr, ') INTO @sample FROM `', v_table,
                '` WHERE ', v_notnull_expr,
                ' GROUP BY ', v_group_expr,
                ' HAVING COUNT(*) > 1 LIMIT 1');
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;

            INSERT INTO collation_precheck_findings
                (table_name, index_name, column_list, sample_value, dup_groups)
            VALUES (v_table, v_index, LEFT(v_columns, 512), LEFT(@sample, 512), v_dups);
        END IF;
    END LOOP;
    CLOSE cur;
END;

CALL precheck_collation_conflicts_2589();
DROP PROCEDURE precheck_collation_conflicts_2589;

-- 衝突があれば「1 表も変換せずに」中断する
DROP PROCEDURE IF EXISTS abort_if_collation_conflicts_2589;

CREATE PROCEDURE abort_if_collation_conflicts_2589()
BEGIN
    DECLARE v_count INT DEFAULT 0;
    DECLARE v_dup_count INT DEFAULT 0;
    DECLARE v_unchk_count INT DEFAULT 0;
    DECLARE v_head VARCHAR(256);

    SELECT COUNT(*),
           SUM(reason = 'DUPLICATE'),
           SUM(reason = 'UNCHECKABLE')
      INTO v_count, v_dup_count, v_unchk_count
      FROM collation_precheck_findings;

    IF v_count > 0 THEN
        -- ⚠️ SIGNAL の MESSAGE_TEXT は MySQL の仕様で 128 文字までで、
        --    超えると ERROR 1648 'Data too long for condition item MESSAGE_TEXT' になり、
        --    本来報告したかった中断理由そのものが失われる。
        --    詳細（索引名・列名・衝突値）は collation_precheck_findings テーブルに残してあるので、
        --    メッセージには件数と参照先だけを 128 文字以内で入れる。
        --    詳細をメッセージに詰めて切り詰めるのは情報の消失であり採らない。
        -- 件数だけではログから原因が分からず、運用者が DB に入って表を引くまで何も掴めない。
        -- 128 文字の範囲で最も情報量の多い要約（先頭 1 件の表.索引と、判定不能ならエラー番号）を載せる。
        SELECT CONCAT(table_name, '.', index_name,
                      IF(reason = 'UNCHECKABLE',
                         CONCAT(' errno=', COALESCE(err_code, 0)), ''))
          INTO v_head
          FROM collation_precheck_findings
         ORDER BY seq
         LIMIT 1;

        SET @msg = LEFT(CONCAT(
            'issue #2589 中断(変換なし) 重複=', COALESCE(v_dup_count, 0),
            ' 判定不能=', COALESCE(v_unchk_count, 0),
            ' 例:', LEFT(COALESCE(v_head, '?'), 55),
            ' 全件:collation_precheck_findings表'), 128);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @msg;
    END IF;
END;

CALL abort_if_collation_conflicts_2589();
DROP PROCEDURE abort_if_collation_conflicts_2589;
DROP PROCEDURE IF EXISTS try_count_dups_2589;

-- =============================================================================
-- STEP 2: 変換前スナップショット
-- 変換が「照合順序以外は何も変えていない」ことを後で機械的に照合するために、
-- 外部キー数・行数・列の型/長さを控えておく。
-- 行数は変換対象表に限定する（全表 COUNT(*) は本番規模では重すぎるため）。
-- =============================================================================
DROP TEMPORARY TABLE IF EXISTS tmp_snapshot_meta_2589;
CREATE TEMPORARY TABLE tmp_snapshot_meta_2589 (
    k VARCHAR(64) PRIMARY KEY,
    v BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_snapshot_meta_2589 (k, v)
SELECT 'fk_count', COUNT(*)
  FROM information_schema.TABLE_CONSTRAINTS
 WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY';

DROP TEMPORARY TABLE IF EXISTS tmp_snapshot_cols_2589;
CREATE TEMPORARY TABLE tmp_snapshot_cols_2589 (
    table_name  VARCHAR(64)  NOT NULL,
    column_name VARCHAR(64)  NOT NULL,
    signature   VARCHAR(512) NOT NULL,
    PRIMARY KEY (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- COLUMN_TYPE には varchar(255) のような長さも含まれるので、型と長さを一括で比較できる。
-- 照合順序は変わることが前提なので署名に含めない。
INSERT INTO tmp_snapshot_cols_2589 (table_name, column_name, signature)
SELECT c.TABLE_NAME, c.COLUMN_NAME,
       CONCAT(c.COLUMN_TYPE, '|', c.IS_NULLABLE, '|', COALESCE(c.EXTRA, ''))
  FROM information_schema.COLUMNS c
  JOIN tmp_collation_targets_2589 t ON t.table_name = c.TABLE_NAME
 WHERE c.TABLE_SCHEMA = DATABASE();

DROP TEMPORARY TABLE IF EXISTS tmp_snapshot_rows_2589;
CREATE TEMPORARY TABLE tmp_snapshot_rows_2589 (
    table_name VARCHAR(64) PRIMARY KEY,
    row_count  BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_verify_rows_2589;
CREATE TEMPORARY TABLE tmp_verify_rows_2589 (
    table_name VARCHAR(64) PRIMARY KEY,
    row_count  BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 変換前後で同じ手順を使うため、格納先を引数で切り替える
DROP PROCEDURE IF EXISTS capture_rowcounts_2589;

CREATE PROCEDURE capture_rowcounts_2589(IN p_target VARCHAR(64))
BEGIN
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_table VARCHAR(64);
    DECLARE cur CURSOR FOR SELECT table_name FROM tmp_collation_targets_2589 ORDER BY seq;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    OPEN cur;
    count_loop: LOOP
        FETCH cur INTO v_table;
        IF v_done = 1 THEN
            LEAVE count_loop;
        END IF;
        SET @sql = CONCAT('INSERT INTO `', p_target, '` (table_name, row_count) ',
                          'SELECT ''', v_table, ''', COUNT(*) FROM `', v_table, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE cur;
END;

CALL capture_rowcounts_2589('tmp_snapshot_rows_2589');

-- =============================================================================
-- STEP 3: 変換
-- =============================================================================

-- データベース既定を固定する。これ以降に作られる表は、
-- サーバ変数 collation_server が何であろうと統一先を継承する（根本原因の除去）。
-- 名前を省略すると既定データベース（＝Flyway の接続先）が対象になる。
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

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
-- （回復していることは STEP 4 が機械的に確認し、駄目なら migration ごと失敗させる）。
SET @prev_fk_checks = @@SESSION.foreign_key_checks;
SET SESSION foreign_key_checks = 0;

CALL unify_table_collation_2589();

SET SESSION foreign_key_checks = @prev_fk_checks;

DROP PROCEDURE unify_table_collation_2589;

-- =============================================================================
-- STEP 4: 検証 — 照合順序が統一され、かつそれ以外は何も変わっていないこと。
-- 1 つでも崩れていたら migration を失敗させる。
-- 黙って通すと「統一したつもり」の嘘が本番に残るため、必ず落とす。
-- 違反した対象の名前をエラーメッセージに載せる（件数だけでは原因調査を始められない）。
-- =============================================================================
CALL capture_rowcounts_2589('tmp_verify_rows_2589');
DROP PROCEDURE capture_rowcounts_2589;

DROP PROCEDURE IF EXISTS verify_table_collation_2589;

CREATE PROCEDURE verify_table_collation_2589()
BEGIN
    DECLARE v_tables TEXT;
    DECLARE v_columns TEXT;
    DECLARE v_diff TEXT;
    DECLARE v_before BIGINT;
    DECLARE v_after BIGINT;

    -- (1) 表の照合順序
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

    -- (2) 列の照合順序。表既定だけ揃えても列に別の照合順序が残っていれば JOIN は同じように落ちる。
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

    -- (3) 外部キー数が不変であること（CONVERT TO で FK が落ちていないか）
    SELECT v INTO v_before FROM tmp_snapshot_meta_2589 WHERE k = 'fk_count';
    SELECT COUNT(*) INTO v_after
      FROM information_schema.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY';

    IF v_before <> v_after THEN
        SET @msg = CONCAT('issue #2589: 変換で外部キー数が変化した（変換前=', v_before,
                          ' 変換後=', v_after, '）');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @msg;
    END IF;

    -- (4) 行数が不変であること
    SELECT GROUP_CONCAT(CONCAT(s.table_name, '(', s.row_count, '->', a.row_count, ')') SEPARATOR ', ')
      INTO v_diff
      FROM tmp_snapshot_rows_2589 s
      JOIN tmp_verify_rows_2589 a ON a.table_name = s.table_name
     WHERE a.row_count <> s.row_count;

    IF v_diff IS NOT NULL THEN
        SET @msg = CONCAT('issue #2589: 変換で行数が変化した: ', LEFT(v_diff, 400));
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @msg;
    END IF;

    -- (5) 列の型・長さ・NULL 可否・EXTRA が不変であること
    --     （CONVERT TO は charset 次第で VARCHAR を昇格させうる。utf8mb4→utf8mb4 では起きないが、
    --       起きていないことを願望でなく実測で押さえる）
    SELECT GROUP_CONCAT(CONCAT(b.table_name, '.', b.column_name, '(', b.signature, ' -> ',
                               COALESCE(CONCAT(c.COLUMN_TYPE, '|', c.IS_NULLABLE, '|',
                                               COALESCE(c.EXTRA, '')), '<消失>'), ')')
                        SEPARATOR ', ')
      INTO v_diff
      FROM tmp_snapshot_cols_2589 b
      LEFT JOIN information_schema.COLUMNS c
             ON c.TABLE_SCHEMA = DATABASE()
            AND c.TABLE_NAME   = b.table_name
            AND c.COLUMN_NAME  = b.column_name
     WHERE c.COLUMN_NAME IS NULL
        OR CONCAT(c.COLUMN_TYPE, '|', c.IS_NULLABLE, '|', COALESCE(c.EXTRA, '')) <> b.signature;

    IF v_diff IS NOT NULL THEN
        SET @msg = CONCAT('issue #2589: 変換で列の型/長さ/NULL可否が変化した: ', LEFT(v_diff, 400));
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @msg;
    END IF;
END;

CALL verify_table_collation_2589();
DROP PROCEDURE verify_table_collation_2589;

DROP TEMPORARY TABLE IF EXISTS tmp_collation_targets_2589;
DROP TABLE IF EXISTS collation_precheck_findings;
DROP TEMPORARY TABLE IF EXISTS tmp_snapshot_meta_2589;
DROP TEMPORARY TABLE IF EXISTS tmp_snapshot_cols_2589;
DROP TEMPORARY TABLE IF EXISTS tmp_snapshot_rows_2589;
DROP TEMPORARY TABLE IF EXISTS tmp_verify_rows_2589;
