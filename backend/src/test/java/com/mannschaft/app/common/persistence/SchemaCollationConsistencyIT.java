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
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
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

    // ---------------------------------------------------------------- helpers

    /** 1 列を文字列として返すクエリを実行する。 */
    private static List<String> query(String sql, String... params) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection conn = connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
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
