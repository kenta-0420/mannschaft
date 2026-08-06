package com.mannschaft.app.common.persistence;

import com.mannschaft.app.common.architecture.SchemaCollationPolicy;
import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>issue #2589: 「ローカルでは通り本番だけ壊れる照合順序のズレ」を恒久的に捕まえる網。</b>
 *
 * <h2>何を防ぐのか</h2>
 * <p>MySQL では照合順序を宣言しない表がサーバ変数 {@code collation_server} を継承する。
 * 本番 RDS とローカル docker でこの変数の値が違っていたため、
 * <b>同じ DDL から環境ごとに違う照合順序のスキーマが生まれ</b>、
 * 照合順序の異なる文字列列同士を JOIN する
 * {@code MyScopeFolderItemRepository#aggregateFolderUnreadCounts} が
 * 本番だけ {@code Illegal mix of collations} で落ちた。
 * 通常のテストは {@code ddl-auto=create} かつ Flyway 無効で走るため、この差は原理的に現れない。</p>
 *
 * <h2>なぜこの形なのか — 個別の JOIN を検査しない理由</h2>
 * <p>「危ない JOIN を列挙して検査する」設計にすると、新しいクエリが書かれるたびに
 * 列挙から漏れる。本 IT は代わりに<b>スキーマ側の不変条件</b>
 * 「全ての表・全ての文字列列が単一の照合順序である」を検査する。
 * この不変条件が成り立つ限り、どんな JOIN を新しく書いても照合不一致は原理的に起こらない。
 * 網の目をクエリではなくスキーマに張ることで、列挙漏れという失敗モードを構造的に消している。</p>
 *
 * <h2>本番と同じ照合順序で走ることの根拠</h2>
 * <p>コンテナを {@code --collation-server=}{@link SchemaCollationPolicy#PRODUCTION_COLLATION_SERVER}
 * で明示起動する。この値は本番 RDS のパラメータグループ {@code collation_server}
 * （{@code infra/terraform/modules/data/main.tf}）と同一である。
 * {@code mysql:8.0} の既定値がたまたま同じであることに依存しない
 * （既定に依存すると、イメージが上がって既定が変わった瞬間に本番との一致が黙って崩れる）。
 * さらに {@link #サーバ既定が本番と同一であること()} が実際の実行時変数を読んで検算するため、
 * 起動オプションが効いていなければテスト自身が落ちる。</p>
 *
 * <h2>なぜ独立クラスなのか</h2>
 * <p>{@code backend/build.gradle.kts} の {@code excludeMigrationTests} は
 * 「{@code /migration/} パッケージ配下 かつ 単純クラス名に {@code Flyway} を含む」クラスを
 * PR CI から除外する。この網は {@code db/migration} を触らない PR でも
 * （たとえば {@code docker-compose.yml} や新しいネイティブクエリの追加でも）効いてほしいので、
 * 除外セレクタの<b>外側</b>に置く。{@code NativeQueryUnsignedBigintTypeIT} と同じ判断である。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.persistence.SchemaCollationConsistencyIT#isDockerAvailable")
@DisplayName("スキーマ全体の照合順序が本番の照合順序で統一されている（issue #2589）")
class SchemaCollationConsistencyIT {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_collation")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            // V13.045 等が CREATE TRIGGER を含むため本番（RDS パラメータグループ）と同条件に揃える。
            .withCommand("--log_bin_trust_function_creators=1",
                    "--character-set-server=" + SchemaCollationPolicy.UNIFIED_CHARSET,
                    "--collation-server=" + SchemaCollationPolicy.PRODUCTION_COLLATION_SERVER);

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void setUp() {
        MYSQL.start();
        try {
            Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("classpath:db/migration")
                    .outOfOrder(false)
                    .load()
                    .migrate();
        } catch (RuntimeException e) {
            // V175 の事前ゲートが発火した場合、詳細は collation_precheck_findings に入っている。
            // SIGNAL の MESSAGE_TEXT は 128 文字までで理由を語り切れないため、
            // ここで表の中身を読み出して例外メッセージに載せる。
            // 落ちたテストが原因を語らないのはテストとして不完全なので、必ず添える。
            throw new IllegalStateException(
                    "Flyway migration に失敗した。V175 事前ゲートの検出内容:\n" + dumpFindings(), e);
        }
    }

    /** {@code collation_precheck_findings} を人間が読める形に整形する（読めなければその旨を返す）。 */
    private static String dumpFindings() {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = connection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT seq, reason, table_name, index_name, column_list, dup_groups, "
                             + "       err_code, err_message, check_sql "
                             + "FROM collation_precheck_findings ORDER BY seq")) {
            while (rs.next()) {
                sb.append("  #").append(rs.getInt("seq"))
                        .append(" [").append(rs.getString("reason")).append("] ")
                        .append(rs.getString("table_name")).append('.')
                        .append(rs.getString("index_name"))
                        .append("\n      列=").append(rs.getString("column_list"));
                Object dup = rs.getObject("dup_groups");
                // NULL は「重複ゼロ」ではなく「計算に到達していない」。
                // 0 と同じ見た目にすると「重複ゼロだから安全」と誤読される。
                sb.append("\n      重複グループ数=")
                        .append(dup == null ? "未計算（検査に到達せず）" : dup)
                        .append("\n      errno=").append(rs.getObject("err_code"))
                        .append(" errmsg=").append(rs.getString("err_message"))
                        .append("\n      検査SQL=").append(rs.getString("check_sql"))
                        .append('\n');
            }
        } catch (SQLException e) {
            return "  （collation_precheck_findings を読めなかった: " + e.getMessage()
                    + "。ゲート以外の原因で失敗した可能性が高い）";
        }
        return sb.length() == 0 ? "  （findings は空。ゲート以外の原因で失敗している）" : sb.toString();
    }

    @AfterAll
    void tearDown() {
        MYSQL.stop();
    }

    /**
     * 本 IT の前提そのものの検算。
     * これが落ちるなら「本番と同じ照合順序で検証している」という主張自体が成立していない。
     */
    @Test
    @DisplayName("検証コンテナのサーバ既定照合順序が本番RDSと同一であること（前提の検算）")
    void サーバ既定が本番と同一であること() throws SQLException {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(readVariable("collation_server"))
                .as("collation_server が本番 RDS のパラメータグループと同一であること。"
                        + "ここが違うなら本 IT は本番と違う条件を測っており、結論に意味が無い")
                .isEqualTo(SchemaCollationPolicy.PRODUCTION_COLLATION_SERVER);
        softly.assertThat(readVariable("character_set_server"))
                .as("character_set_server が本番 RDS と同一であること")
                .isEqualTo(SchemaCollationPolicy.UNIFIED_CHARSET);
        softly.assertAll();
    }

    /**
     * 全ての基底テーブルが統一照合順序であること。
     *
     * <p>{@code flyway_schema_history} は Flyway が自前で作る管理表であり
     * アプリのクエリが JOIN することは無いので対象外。ビューは
     * {@code TABLE_COLLATION} が {@code NULL} になるため {@code BASE TABLE} に限定する。</p>
     */
    @Test
    @DisplayName("全テーブルの既定照合順序が統一されている")
    void 全テーブルの照合順序が統一されている() throws SQLException {
        List<String> violations = query(
                "SELECT CONCAT(TABLE_NAME, ' -> ', TABLE_COLLATION) "
                        + "FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_TYPE = 'BASE TABLE' "
                        + "  AND TABLE_NAME <> 'flyway_schema_history' "
                        + "  AND TABLE_COLLATION <> ? "
                        + "ORDER BY TABLE_NAME",
                SchemaCollationPolicy.UNIFIED_COLLATION);

        assertThat(violations)
                .as("照合順序が %s でないテーブルが存在する。"
                        + "宣言を忘れた新規表はサーバ既定を継承するため、"
                        + "本番とローカルで照合順序が食い違い『ローカルでは通る JOIN が本番だけ落ちる』"
                        + "（issue #2589）。新規テーブルの CREATE TABLE には "
                        + "`DEFAULT CHARSET=%s COLLATE=%s` を明示すること。違反=%s",
                        SchemaCollationPolicy.UNIFIED_COLLATION,
                        SchemaCollationPolicy.UNIFIED_CHARSET,
                        SchemaCollationPolicy.UNIFIED_COLLATION,
                        violations)
                .isEmpty();
    }

    /**
     * 全ての文字列列が統一照合順序であること。
     *
     * <p>表既定だけ揃っていても、列単位で {@code COLLATE} を書けば個別に上書きできてしまう。
     * 実際に比較されるのは列であって表ではないので、列そのものを検査する。
     * {@code COLLATION_NAME IS NULL} は非文字列列（数値・日時・バイナリ）なので対象外。</p>
     */
    @Test
    @DisplayName("全文字列列の照合順序が統一されている（列単位の上書きも許さない）")
    void 全文字列列の照合順序が統一されている() throws SQLException {
        List<String> violations = query(
                "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, ' -> ', COLLATION_NAME) "
                        + "FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND TABLE_NAME <> 'flyway_schema_history' "
                        + "  AND COLLATION_NAME IS NOT NULL "
                        + "  AND COLLATION_NAME <> ? "
                        + "ORDER BY TABLE_NAME, COLUMN_NAME",
                SchemaCollationPolicy.UNIFIED_COLLATION);

        assertThat(violations)
                .as("照合順序が %s でない文字列列が存在する。"
                        + "列単位の COLLATE 上書きは JOIN 相手との不一致を生むため禁止。違反=%s",
                        SchemaCollationPolicy.UNIFIED_COLLATION, violations)
                .isEmpty();
    }

    /**
     * データベース既定が固定されていること。
     *
     * <p>これが統一値であれば、以後に作られる表は宣言を忘れても正しい照合順序を継承する。
     * つまり「宣言忘れ」という人的ミスが本番障害に化けなくなる最後の砦であり、
     * 番人テストをすり抜けた場合の多重防御にあたる。</p>
     */
    @Test
    @DisplayName("データベース既定の照合順序が固定されており、宣言忘れでも正しい値を継承する")
    void データベース既定が固定されている() throws SQLException {
        List<String> actual = query(
                "SELECT CONCAT(DEFAULT_CHARACTER_SET_NAME, '/', DEFAULT_COLLATION_NAME) "
                        + "FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE()");

        assertThat(actual)
                .as("V175 の ALTER DATABASE によりデータベース既定が固定されていること。"
                        + "固定されていれば表の照合順序はサーバ変数 collation_server に依存しなくなる")
                .containsExactly(SchemaCollationPolicy.UNIFIED_CHARSET + "/"
                        + SchemaCollationPolicy.UNIFIED_COLLATION);
    }

    /**
     * 実際に壊れていた JOIN が、本番と同じ条件で通ることの直接確認。
     *
     * <p>不変条件（上記）が成り立てば理屈の上では落ちないが、
     * 「理屈では大丈夫」で本番が壊れたのが本 issue なので、実物を 1 本走らせて裏を取る。</p>
     */
    @Test
    @DisplayName("かつて本番だけ落ちていた notifications × my_scope_folders の JOIN が通る")
    void かつて落ちていたJOINが通る() throws SQLException {
        // 行が 0 件でも照合順序の検査はパース〜実行時に行われるため、例外が出ないことが検証になる。
        List<String> rows = query(
                "SELECT CONCAT(folder.id, '') FROM my_scope_folders folder "
                        + "LEFT JOIN my_scope_folder_items item ON item.folder_id = folder.id "
                        + "LEFT JOIN notifications n "
                        + "  ON n.scope_id = item.scope_id "
                        + "  AND n.scope_type = folder.scope_type "
                        + "GROUP BY folder.id");

        assertThat(rows)
                .as("照合不一致なら executeQuery が SQLException(Illegal mix of collations) を投げる。"
                        + "例外なく到達すること自体が検証内容であり、件数は問わない")
                .isNotNull();
    }

    /**
     * <b>STORED な文字列生成列を持つ表が {@code CONVERT TO CHARACTER SET} に耐えることの実証。</b>
     *
     * <p>生成列は式から値が決まるため、照合順序を変える再構築で問題を起こしやすい類型である。
     * 本スキーマには {@code user_roles.scope_key}（{@code V2.006}）のように
     * <b>STORED 生成列がパーシャルユニークキーの実体になっている</b>例があり、
     * 「再構築で式が再評価され、新しい照合順序の下で一意制約に触れる」経路が理屈の上では存在する。</p>
     *
     * <p>そこで<b>実際に {@code CONVERT TO} を流して確かめる</b>。
     * V175 は既にこれらの表を変換済みなので、ここでの再実行は冪等な no-op に見えるが、
     * MySQL は CONVERT TO を受けると内容が同じでも表を作り直すため、
     * 生成列の再評価と索引の再構築という<b>同じ経路を確かに通る</b>。
     * 例外が飛ばずに完走することが検証内容である。</p>
     *
     * <p>対象表は静的に列挙せず {@code information_schema} から動的に導出する
     * （#2589 で静的列挙が実体と食い違った反省による）。
     * 併せて対象が 0 件でないことを表明し、列挙が空振りしてテストが
     * 無内容に緑になることを防ぐ。</p>
     */
    @Test
    @DisplayName("STORED な文字列生成列を持つ表が CONVERT TO に耐える（生成列の再評価を実地で確認）")
    void STORED生成列を持つ表が変換に耐える() throws SQLException {
        List<String> targets = query(
                "SELECT DISTINCT TABLE_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND EXTRA LIKE '%STORED GENERATED%' "
                        + "  AND COLLATION_NAME IS NOT NULL "
                        + "ORDER BY TABLE_NAME");

        assertThat(targets)
                .as("STORED な文字列生成列を持つ表が 1 つ以上あること。"
                        + "0 件ならこのテストは何も検証しておらず、"
                        + "列挙の書き間違いで無内容に緑になっている可能性がある")
                .isNotEmpty();

        SoftAssertions softly = new SoftAssertions();
        for (String table : targets) {
            try (Connection conn = connection();
                 Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE `" + table + "` CONVERT TO CHARACTER SET "
                        + SchemaCollationPolicy.UNIFIED_CHARSET + " COLLATE "
                        + SchemaCollationPolicy.UNIFIED_COLLATION);
            } catch (SQLException e) {
                softly.fail("STORED 生成列を持つ %s の CONVERT TO が失敗した。"
                        + "本番適用時に同じ箇所で migration が途中停止する: %s", table, e.getMessage());
            }
        }
        softly.assertAll();

        // 変換後も統一が保たれていること（生成列の再評価で照合順序が戻っていないか）
        List<String> violations = query(
                "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, ' -> ', COLLATION_NAME) "
                        + "FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "  AND EXTRA LIKE '%STORED GENERATED%' "
                        + "  AND COLLATION_NAME IS NOT NULL "
                        + "  AND COLLATION_NAME <> ?",
                SchemaCollationPolicy.UNIFIED_COLLATION);

        assertThat(violations)
                .as("再変換後も STORED 生成列の照合順序が統一されていること。違反=%s", violations)
                .isEmpty();
    }

    /**
     * <b>V175 STEP 1 の事前重複検出ゲートが、実際に衝突を検出できることの実証。</b>
     *
     * <h2>なぜこのテストが要るか</h2>
     * <p>V175 は「変換で一意制約違反が起きないか」を実データで事前検査し、
     * 衝突があれば 1 表も変換せずに中断する。しかし migration が Flyway で流れる
     * テストスキーマには業務データが無いため、<b>ゲートは毎回「衝突 0 件」で素通りする</b>。
     * つまり通常の IT では<b>検出できることが一度も確かめられない</b>。
     * 検出しない安全装置は無いのと同じなので、衝突を人為的に作って発火を確認する。</p>
     *
     * <h2>何を衝突させるか</h2>
     * <p>ASCII のゼロ {@code '0'}（U+0030）と NKo のゼロ（U+07C0）を使う。
     * この 2 文字は {@code WEIGHT_STRING()} の全数比較（65,502 コードポイント）で
     * 「{@code utf8mb4_unicode_ci} では区別され {@code utf8mb4_0900_ai_ci} では同一」
     * と実測された 666 グループのうちの 1 つであり、実際にペア単位でも検算済みである。</p>
     *
     * <p><b>全角/半角の約物を使わない理由</b>: {@code ','} と {@code '，'}、
     * {@code 'A'} と {@code 'Ａ'}、{@code '。'} と {@code '｡'} は
     * <b>どちらの照合順序でも既に等価</b>であり（実測）、衝突を作れない。
     * 変換で新たに等価化するのは、異なる字体系の数字や
     * 縦書き用の異体（U+FE10 等）といった、より限定的な文字である。</p>
     *
     * <p>検査するのは V175 STEP 1 が組み立てるのと同じ述語
     * （{@code GROUP BY <列> COLLATE <統一先> HAVING COUNT(*) > 1}）である。</p>
     */
    @Test
    @DisplayName("事前重複検出ゲートが照合順序変更で生じる衝突を実際に検出する（発火の実証）")
    void 事前重複検出ゲートが衝突を検出する() throws SQLException {
        final String table = "tmp_precheck_probe_2589";
        try (Connection conn = connection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS `" + table + "`");
            // 変換前の照合順序（utf8mb4_unicode_ci）では別物として共存できる 2 行を作る
            st.execute("CREATE TABLE `" + table + "` ("
                    + " id BIGINT PRIMARY KEY,"
                    + " code VARCHAR(32) COLLATE utf8mb4_unicode_ci,"
                    + " UNIQUE KEY uq_code (code)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            // '0'(U+0030) と NKo DIGIT ZERO(U+07C0)。unicode_ci では別物なので共存できる。
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO `" + table + "` (id, code) VALUES (1, ?), (2, ?)")) {
                ps.setString(1, "a0b");
                // 2 文字目は NKo DIGIT ZERO (U+07C0)。ASCII の '0' とは別文字である。
                ps.setString(2, "a߀b");
                ps.executeUpdate();
            }

            // 前提の検算: 変換前は UNIQUE 制約に共存できている（＝現状は衝突していない）
            List<String> before = query("SELECT COUNT(*) FROM `" + table + "`");
            assertThat(before).as("変換前は 2 行が共存できること").containsExactly("2");

            // V175 STEP 1 と同じ述語で検査する
            // ANY_VALUE() で包むのは必須。MySQL 8.0 既定の sql_mode=ONLY_FULL_GROUP_BY では
            // 「COLLATE 付きでグループ化した列」を素のまま SELECT できず ERROR 1055 になる。
            // V175 STEP 1 の報告用 SQL もまったく同じ形をしているので、
            // ここで同じ書き方を検証しておくことが本番での構文エラーの再発防止になる。
            List<String> conflicts = query(
                    "SELECT CONCAT(ANY_VALUE(code), ' x', COUNT(*)) FROM `" + table + "`"
                            + " WHERE code IS NOT NULL"
                            + " GROUP BY code COLLATE " + SchemaCollationPolicy.UNIFIED_COLLATION
                            + " HAVING COUNT(*) > 1");

            assertThat(conflicts)
                    .as("事前検査の述語が『変換すると一意制約に違反する組』を検出すること。"
                            + "ここが空なら V175 STEP 1 は衝突を素通しし、"
                            + "本番の変換途中で ERROR 1062 により停止して混在状態が残る")
                    .hasSize(1);

            // 実際に変換すると本当に失敗することも確かめる（ゲートの必要性そのものの裏取り）
            assertThatThrownBy(() -> {
                try (Connection c2 = connection();
                     Statement s2 = c2.createStatement()) {
                    s2.execute("ALTER TABLE `" + table + "` CONVERT TO CHARACTER SET "
                            + SchemaCollationPolicy.UNIFIED_CHARSET + " COLLATE "
                            + SchemaCollationPolicy.UNIFIED_COLLATION);
                }
            })
                    .as("ゲートが無ければ CONVERT TO は一意制約違反で落ちる。"
                            + "この失敗こそが V175 STEP 1 の存在理由である")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Duplicate entry");
        } finally {
            try (Connection conn = connection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        }
    }

    /**
     * <b>事前ゲートが UNIQUE 索引のキーパートを 1 本も取りこぼしていないことの検証。</b>
     *
     * <h2>なぜこの検証が要るか</h2>
     * <p>ゲートは {@code information_schema.STATISTICS} と {@code COLUMNS} を突き合わせて
     * グループ化式を組み立てる。ここが <b>INNER JOIN</b> だと、MySQL 8.0 の関数インデックス
     * （{@code STATISTICS} 上で {@code COLUMN_NAME IS NULL} ＋ {@code EXPRESSION} 有り。
     * 実体の隠し仮想生成列は {@code COLUMNS} に現れない）のキーパートが<b>丸ごと脱落する</b>。</p>
     *
     * <p>脱落すると本来より<b>粗い</b>グループ化式になり、実害は 2 方向に出る:</p>
     * <ul>
     *   <li><b>偽陽性</b>: 照合順序と無関係な重複を拾い、変換の必要が無いのに migration が中断する。
     *       しかも runbook に従った運用者が「衝突データの正規化」＝実利用者データの削除に向かう</li>
     *   <li><b>偽陰性</b>: 脱落した式が参照する文字列列の真の衝突が検査されない</li>
     * </ul>
     *
     * <p>本スキーマには実際に関数インデックスが存在する
     * （{@code recruitment_subcategories.uk_rs_active_name} / {@code V3.117}。
     * 論理削除済みを UNIQUE 対象外にする部分索引の代替）。</p>
     *
     * <h2>検証方法</h2>
     * <p>ゲートのグループ化式そのものを再実装すると同じ誤りを二度書くことになるので、
     * <b>壊れた性質そのもの</b>——「STATISTICS 上のキーパート数」と
     * 「ゲートの JOIN 後に残るキーパート数」が一致すること——を検証する。
     * INNER JOIN に戻せば関数パートの分だけ数が合わなくなり、このテストが落ちる。</p>
     */
    @Test
    @DisplayName("事前ゲートがUNIQUE索引のキーパートを取りこぼさない（関数インデックス脱落の回帰ガード）")
    void 事前ゲートがキーパートを取りこぼさない() throws SQLException {
        // 前提の検算: 関数キーパートを持つ UNIQUE 索引がこのスキーマに実在すること。
        // 0 件ならこのテストは何も守っておらず、無内容に緑になっている。
        List<String> functional = query(
                "SELECT CONCAT(TABLE_NAME, '.', INDEX_NAME) "
                        + "FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0 "
                        + "  AND EXPRESSION IS NOT NULL "
                        + "GROUP BY TABLE_NAME, INDEX_NAME");

        assertThat(functional)
                .as("関数キーパートを持つ UNIQUE 索引が実在すること（本テストの前提）")
                .isNotEmpty();

        // ゲートと同じ JOIN 条件で、キーパートが脱落しないことを確認する。
        List<String> lost = query(
                "SELECT CONCAT(s.TABLE_NAME, '.', s.INDEX_NAME, ' 全', COUNT(*), "
                        + "       '本中 突合できたのは', SUM(c.COLUMN_NAME IS NOT NULL OR s.EXPRESSION IS NOT NULL), '本') "
                        + "FROM information_schema.STATISTICS s "
                        + "LEFT JOIN information_schema.COLUMNS c "
                        + "  ON c.TABLE_SCHEMA = s.TABLE_SCHEMA "
                        + " AND c.TABLE_NAME   = s.TABLE_NAME "
                        + " AND c.COLUMN_NAME  = s.COLUMN_NAME "
                        + "WHERE s.TABLE_SCHEMA = DATABASE() "
                        + "  AND s.NON_UNIQUE = 0 "
                        + "  AND s.INDEX_TYPE <> 'FULLTEXT' "
                        + "GROUP BY s.TABLE_NAME, s.INDEX_NAME "
                        + "HAVING SUM(c.COLUMN_NAME IS NOT NULL OR s.EXPRESSION IS NOT NULL) <> COUNT(*)");

        assertThat(lost)
                .as("ゲートの突合でキーパートが脱落する UNIQUE 索引が無いこと。"
                        + "脱落するとグループ化式が粗くなり、偽陽性（無関係な重複で migration 中断）と"
                        + "偽陰性（真の衝突見逃し）が同時に起きる。違反=%s", lost)
                .isEmpty();
    }

    /**
     * <b>関数インデックスが「検査対象に入り、重複判定まで到達する」ことの直接検証。</b>
     *
     * <h2>なぜ「取りこぼしが無い」だけでは足りないか</h2>
     * <p>{@link #事前ゲートがキーパートを取りこぼさない()} は突合の段階でキーパートが
     * 落ちないことを見るが、<b>組み立てた SQL が実際に実行できるか</b>は見ていない。
     * 実際、式に一律 {@code COLLATE} を付けていた時期は、数値・日時を返す式に対して
     * {@code ERROR 1253} で実行できず、ゲートは全環境で {@code UNCHECKABLE} を立てて
     * migration を必ず中断させていた（＝この PR が永久に適用できない状態だった）。
     * 「対象に入っている」ことと「判定できている」ことは別なので、後者を直接確かめる。</p>
     *
     * <h2>検証内容</h2>
     * <p>関数キーパートを持つ UNIQUE 索引について、migration と同じ形の
     * グループ化式を組み立てて<b>実際に重複件数を数える</b>。
     * 例外なく数値が返ることが、ゲートがその索引を判定できている証拠になる。</p>
     */
    @Test
    @DisplayName("関数インデックスが検査対象に入り重複判定まで到達する（ERROR 1253 の回帰ガード）")
    void 関数インデックスが重複判定まで到達する() throws SQLException {
        // migration と同じ規則でグループ化式・NOT NULL 条件を組み立てる
        // 統一照合順序はコード内の定数であって外部入力ではないので、SQL に直接埋め込む。
        // （プレースホルダにすると SQL 文字列リテラルの中の '?' となり、
        //   JDBC はそれをパラメータとみなさないためバインド数が食い違う）
        final String coll = SchemaCollationPolicy.UNIFIED_COLLATION;
        // migration と同じく EXPRESSION のバックスラッシュエスケープを解除する。
        // ここを migration と揃えておかないと、ERROR 1064 の回帰をこのテストが見逃す。
        final String unesc = "REPLACE(s.EXPRESSION, CONCAT(CHAR(92), CHAR(39)), CHAR(39))";

        List<String> plans = query(
                "SELECT CONCAT(s.TABLE_NAME, '<@>', "
                        + " GROUP_CONCAT(CASE WHEN s.EXPRESSION IS NOT NULL "
                        + "        THEN CONCAT('CAST((', " + unesc + ", ') AS CHAR) COLLATE " + coll + "') "
                        + "        WHEN c.COLLATION_NAME IS NULL THEN CONCAT('`', s.COLUMN_NAME, '`') "
                        + "        WHEN s.SUB_PART IS NULL "
                        + "        THEN CONCAT('`', s.COLUMN_NAME, '` COLLATE " + coll + "') "
                        + "        ELSE CONCAT('LEFT(`', s.COLUMN_NAME, '`, ', s.SUB_PART, "
                        + "                    ') COLLATE " + coll + "') "
                        + "   END ORDER BY s.SEQ_IN_INDEX SEPARATOR ', '), '<@>', "
                        + " GROUP_CONCAT(CASE WHEN s.EXPRESSION IS NOT NULL "
                        + "        THEN CONCAT('(', " + unesc + ", ') IS NOT NULL') "
                        + "        ELSE CONCAT('`', s.COLUMN_NAME, '` IS NOT NULL') "
                        + "   END ORDER BY s.SEQ_IN_INDEX SEPARATOR ' AND ')) "
                        + "FROM information_schema.STATISTICS s "
                        + "LEFT JOIN information_schema.COLUMNS c "
                        + "  ON c.TABLE_SCHEMA = s.TABLE_SCHEMA AND c.TABLE_NAME = s.TABLE_NAME "
                        + " AND c.COLUMN_NAME = s.COLUMN_NAME "
                        + "WHERE s.TABLE_SCHEMA = DATABASE() AND s.NON_UNIQUE = 0 "
                        + "  AND s.INDEX_TYPE <> 'FULLTEXT' "
                        + "GROUP BY s.TABLE_NAME, s.INDEX_NAME "
                        + "HAVING SUM(s.EXPRESSION IS NOT NULL) > 0");

        assertThat(plans)
                .as("関数キーパートを持つ UNIQUE 索引が実在すること（本テストの前提）")
                .isNotEmpty();

        SoftAssertions softly = new SoftAssertions();
        for (String plan : plans) {
            String[] p = plan.split("<@>", -1);
            String table = p[0];
            String sql = "SELECT COUNT(*) FROM (SELECT 1 FROM `" + table + "`"
                    + " WHERE " + p[2] + " GROUP BY " + p[1] + " HAVING COUNT(*) > 1) g";
            try {
                List<String> r = query(sql);
                // 「例外が出なかった」だけでは不十分。
                // dup 計算まで到達して実数が返ったこと（NULL でないこと）が到達の証明である。
                softly.assertThat(r)
                        .as("%s の重複件数が 1 行返ること", table)
                        .hasSize(1);
                if (r.size() == 1) {
                    softly.assertThat(r.get(0))
                            .as("%s の重複件数が NULL ではなく実数であること。"
                                    + "NULL は『検査に到達していない』を意味し、"
                                    + "ゲートはその索引を UNCHECKABLE として migration を中断させる", table)
                            .isNotNull()
                            .matches("\\d+");
                }
            } catch (SQLException e) {
                softly.fail("関数インデックスを含む %s の重複判定が実行できない。"
                        + "ゲートはこの索引を UNCHECKABLE として扱い migration を中断させる: %s",
                        table, e.getMessage());
            }
        }
        softly.assertAll();
    }

    // ---------------------------------------------------------------- helpers

    /** 1 列を文字列として返すクエリを実行する。 */
    private static List<String> query(String sql, String... params) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // バインド数の食い違いは JDBC の内部例外
            // （getCoreParameterIndex の ArrayIndexOutOfBounds 相当）として現れ、
            // 「SQL のどこが悪いのか」が読み取れないスタックトレースになる。
            // 本ヘルパの呼び出し側の誤りなので、ここで意味のあるメッセージにして落とす。
            // 特に、SQL 文字列リテラルの中に書いた '?' は JDBC がパラメータとみなさないため、
            // 引数だけ増えて食い違う事故が起きやすい（実際に起きた）。
            int expected = ps.getParameterMetaData().getParameterCount();
            if (expected != params.length) {
                throw new IllegalArgumentException(
                        "プレースホルダ数と引数の数が一致しない: SQL 側=" + expected
                                + " 引数=" + params.length
                                + "（SQL 文字列リテラル内の '?' はパラメータとして数えられない点に注意）"
                                + " SQL=" + sql);
            }
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        }
        return result;
    }

    /** セッションのシステム変数を読む。 */
    private static String readVariable(String name) throws SQLException {
        try (Connection conn = connection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT @@GLOBAL." + name)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
