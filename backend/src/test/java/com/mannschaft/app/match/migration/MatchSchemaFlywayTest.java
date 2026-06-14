package com.mannschaft.app.match.migration;

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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.10 の 3 テーブル（matches / match_events / player_appearances）の
 * <b>Flyway 実スキーマ（from-scratch 適用）</b>に対する構造検証テスト。
 *
 * <h2>検証内容（設計 01 §A.4 / §B）</h2>
 * <ul>
 *   <li>全マイグレーションが fresh DB にバージョン順で適用できること。</li>
 *   <li>3 テーブルの主キーが BINARY(16)（UUIDv7・原則6）であること。</li>
 *   <li><b>子テーブル（match_events / player_appearances）に organization_id / deleted_at が無い</b>こと
 *       （テナント分離は親 matches・01 §A.4）。逆に親 matches には両列があること。</li>
 *   <li>FK が同一 match ドメイン内のみ張られ、CASCADE / SET NULL が正しいこと
 *       （子.match_id→matches CASCADE / match_events.linked_event_id→match_events SET NULL）。</li>
 *   <li>クロスドメイン FK が存在しないこと（matches/子テーブルから他ドメインへの FK 0 件）。</li>
 * </ul>
 *
 * <p>{@code FlywayFromScratchMigrationTest} と同様、Spring コンテキストを起動せず Testcontainers の
 * 実 MySQL 8.0 に Flyway を Java API で直接適用する（application-test.yml の flyway.enabled=false を回避）。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（テストは骨抜きにせず正しく書く・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.match.migration.MatchSchemaFlywayTest#isDockerAvailable")
@DisplayName("F08.10 match スキーマ Flyway 構造検証テスト（from-scratch）")
class MatchSchemaFlywayTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_match_schema")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

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

    private Connection conn() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private boolean columnExists(String table, String column) throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.columns"
                             + " WHERE table_schema = DATABASE()"
                             + " AND table_name = '" + table + "'"
                             + " AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private String columnType(String table, String column) throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT column_type FROM information_schema.columns"
                             + " WHERE table_schema = DATABASE()"
                             + " AND table_name = '" + table + "'"
                             + " AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("4 テーブルが作成され主キーが BINARY(16)（UUIDv7・原則6）")
    void tablesExistWithBinaryPk() throws Exception {
        for (String table : List.of("matches", "match_events", "player_appearances", "match_sets")) {
            assertThat(columnExists(table, "id"))
                    .as("%s.id が存在すること", table).isTrue();
            assertThat(columnType(table, "id").toLowerCase())
                    .as("%s.id は binary(16)（UUIDv7）", table)
                    .isEqualTo("binary(16)");
        }
    }

    @Test
    @DisplayName("親 matches は organization_id / deleted_at を持つ（テナント分離・論理削除）")
    void parentHasTenantAndSoftDeleteColumns() throws Exception {
        assertThat(columnExists("matches", "organization_id")).isTrue();
        assertThat(columnExists("matches", "deleted_at")).isTrue();
    }

    @Test
    @DisplayName("子テーブルは organization_id / deleted_at を持たない（01 §A.4・二段アクセス）")
    void childrenHaveNoTenantNorSoftDeleteColumns() throws Exception {
        for (String child : List.of("match_events", "player_appearances", "match_sets")) {
            assertThat(columnExists(child, "organization_id"))
                    .as("%s に organization_id があってはならない（テナント分離は親 matches）", child).isFalse();
            assertThat(columnExists(child, "deleted_at"))
                    .as("%s に deleted_at があってはならない（親 matches の論理削除に従う）", child).isFalse();
        }
    }

    @Test
    @DisplayName("tournament_fixture_id / schedule_id は BIGINT 据え置き（原則6・クロスドメイン ID 参照）")
    void crossDomainIdsAreBigint() throws Exception {
        assertThat(columnType("matches", "tournament_fixture_id").toLowerCase()).startsWith("bigint");
        assertThat(columnType("matches", "schedule_id").toLowerCase()).startsWith("bigint");
    }

    /** 当該テーブルの FK 一覧（制約名, 参照先テーブル, ON DELETE ルール）。 */
    private List<String[]> foreignKeys(String table) throws Exception {
        List<String[]> result = new ArrayList<>();
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT rc.constraint_name, kcu.referenced_table_name, rc.delete_rule"
                             + " FROM information_schema.referential_constraints rc"
                             + " JOIN information_schema.key_column_usage kcu"
                             + "   ON rc.constraint_name = kcu.constraint_name"
                             + "  AND rc.constraint_schema = kcu.table_schema"
                             + " WHERE rc.constraint_schema = DATABASE()"
                             + "   AND rc.table_name = '" + table + "'")) {
            while (rs.next()) {
                result.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            }
        }
        return result;
    }

    @Test
    @DisplayName("match_events の FK は match_id→matches(CASCADE) と linked_event_id→match_events(SET NULL) のみ")
    void matchEventsForeignKeys() throws Exception {
        List<String[]> fks = foreignKeys("match_events");
        // 参照先はすべて同一 match ドメイン（matches / match_events）であること（クロスドメイン FK なし）
        assertThat(fks)
                .as("match_events の FK 参照先は matches / match_events のみ（クロスドメイン FK 禁止）")
                .allSatisfy(fk -> assertThat(fk[1]).isIn("matches", "match_events"));

        assertThat(fks)
                .anySatisfy(fk -> {
                    assertThat(fk[1]).isEqualTo("matches");
                    assertThat(fk[2]).isEqualTo("CASCADE");
                });
        assertThat(fks)
                .anySatisfy(fk -> {
                    assertThat(fk[1]).isEqualTo("match_events");
                    assertThat(fk[2]).isEqualTo("SET NULL");
                });
    }

    @Test
    @DisplayName("player_appearances の FK は match_id→matches(CASCADE) のみ（クロスドメイン FK なし）")
    void playerAppearancesForeignKeys() throws Exception {
        List<String[]> fks = foreignKeys("player_appearances");
        assertThat(fks)
                .as("player_appearances の FK 参照先は matches のみ")
                .allSatisfy(fk -> assertThat(fk[1]).isEqualTo("matches"));
        assertThat(fks)
                .anySatisfy(fk -> assertThat(fk[2]).isEqualTo("CASCADE"));
    }

    @Test
    @DisplayName("match_sets の FK は match_id→matches(CASCADE) のみ（同一ドメイン・クロスドメイン FK なし・§B.5）")
    void matchSetsForeignKeys() throws Exception {
        List<String[]> fks = foreignKeys("match_sets");
        assertThat(fks)
                .as("match_sets の FK 参照先は matches のみ（クロスドメイン FK 禁止・原則1）")
                .allSatisfy(fk -> assertThat(fk[1]).isEqualTo("matches"));
        assertThat(fks)
                .anySatisfy(fk -> {
                    assertThat(fk[1]).isEqualTo("matches");
                    assertThat(fk[2]).isEqualTo("CASCADE");
                });
    }

    @Test
    @DisplayName("match_sets の列構成（set_number/home_points/away_points/winner_side/is_final_set・§B.5）")
    void matchSetsColumns() throws Exception {
        assertThat(columnExists("match_sets", "match_id")).isTrue();
        assertThat(columnType("match_sets", "match_id").toLowerCase()).isEqualTo("binary(16)");
        assertThat(columnExists("match_sets", "set_number")).isTrue();
        assertThat(columnExists("match_sets", "home_points")).isTrue();
        assertThat(columnExists("match_sets", "away_points")).isTrue();
        assertThat(columnExists("match_sets", "winner_side")).isTrue();
        assertThat(columnExists("match_sets", "is_final_set")).isTrue();
        // winner_side は NULL 許容（未決着セット・§4.2）
        assertThat(columnIsNullable("match_sets", "winner_side"))
                .as("winner_side は NULL 許容（未決着セットは勝者なし）").isTrue();
        // home_points/away_points は NOT NULL DEFAULT 0
        assertThat(columnIsNullable("match_sets", "home_points")).isFalse();
        assertThat(columnIsNullable("match_sets", "away_points")).isFalse();
    }

    @Test
    @DisplayName("matches はクロスドメイン FK を持たない（原則1・ID 参照のみ）")
    void matchesHasNoForeignKeys() throws Exception {
        assertThat(foreignKeys("matches"))
                .as("matches から他ドメインへの FK は張らない（原則1）")
                .isEmpty();
    }

    private boolean columnIsNullable(String table, String column) throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT is_nullable FROM information_schema.columns"
                             + " WHERE table_schema = DATABASE()"
                             + " AND table_name = '" + table + "'"
                             + " AND column_name = '" + column + "'")) {
            rs.next();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }

    private String columnDefault(String table, String column) throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT column_default FROM information_schema.columns"
                             + " WHERE table_schema = DATABASE()"
                             + " AND table_name = '" + table + "'"
                             + " AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("matches.state_model は NOT NULL DEFAULT 'CONTINUOUS_TIME'（V85.001・01 §D.6）")
    void matchesHasStateModelColumn() throws Exception {
        assertThat(columnExists("matches", "state_model"))
                .as("matches.state_model が存在すること（V85.001）").isTrue();
        assertThat(columnType("matches", "state_model").toLowerCase())
                .as("state_model は varchar(16)").isEqualTo("varchar(16)");
        assertThat(columnIsNullable("matches", "state_model"))
                .as("state_model は NOT NULL").isFalse();
        assertThat(columnDefault("matches", "state_model"))
                .as("state_model の DEFAULT は CONTINUOUS_TIME（既存 SOCCER 行の後方互換充填）")
                .isEqualTo("CONTINUOUS_TIME");
    }

    @Test
    @DisplayName("match_events.period は NULL 許容（V85.001・ターン制は NULL・01 §B.2/§D.6）")
    void matchEventsPeriodIsNullable() throws Exception {
        assertThat(columnIsNullable("match_events", "period"))
                .as("period は NULL 許容（ターン制＝将棋/囲碁は period を使わない）").isTrue();
    }
}
