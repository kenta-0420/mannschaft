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
    @DisplayName("matches はクロスドメイン FK を持たない（原則1・自己参照 FK のみ許容・V87.001 後）")
    void matchesHasNoCrossDomainForeignKeys() throws Exception {
        // V87.001 で団体戦の自己参照 FK（parent_match_id→matches）が追加される。
        // クロスドメイン FK は依然 0 件＝matches から張る FK の参照先は自身（matches）のみであること（原則1）。
        assertThat(foreignKeys("matches"))
                .as("matches から張る FK の参照先は自身（matches）のみ＝クロスドメイン FK は無い（原則1）")
                .allSatisfy(fk -> assertThat(fk[1]).isEqualTo("matches"));
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

    // ─── V87.001: ターン制（将棋/囲碁）＋団体戦の matches 4 列＋自己参照 FK ───

    @Test
    @DisplayName("matches.total_moves は SMALLINT UNSIGNED NULL（V87.001・ターン制のみ・01 §B.1）")
    void matchesHasTotalMovesColumn() throws Exception {
        assertThat(columnExists("matches", "total_moves"))
                .as("matches.total_moves が存在すること（V87.001）").isTrue();
        assertThat(columnType("matches", "total_moves").toLowerCase())
                .as("total_moves は smallint unsigned").contains("smallint").contains("unsigned");
        assertThat(columnIsNullable("matches", "total_moves"))
                .as("total_moves は NULL 許容（球技は NULL・後方互換）").isTrue();
    }

    @Test
    @DisplayName("matches.win_method は VARCHAR(32) NULL（V87.001・ターン制のみ・01 §D.7）")
    void matchesHasWinMethodColumn() throws Exception {
        assertThat(columnExists("matches", "win_method"))
                .as("matches.win_method が存在すること（V87.001）").isTrue();
        assertThat(columnType("matches", "win_method").toLowerCase())
                .as("win_method は varchar(32)").isEqualTo("varchar(32)");
        assertThat(columnIsNullable("matches", "win_method"))
                .as("win_method は NULL 許容（球技は NULL・団体戦の親も NULL）").isTrue();
    }

    @Test
    @DisplayName("matches.parent_match_id は BINARY(16) NULL（V87.001・団体戦の親 match・自己参照・01 §B.6）")
    void matchesHasParentMatchIdColumn() throws Exception {
        assertThat(columnExists("matches", "parent_match_id"))
                .as("matches.parent_match_id が存在すること（V87.001）").isTrue();
        assertThat(columnType("matches", "parent_match_id").toLowerCase())
                .as("parent_match_id は binary(16)（matches.id 自己参照）").isEqualTo("binary(16)");
        assertThat(columnIsNullable("matches", "parent_match_id"))
                .as("parent_match_id は NULL 許容（個人戦・団体戦の親は NULL）").isTrue();
    }

    @Test
    @DisplayName("matches.board_number は SMALLINT UNSIGNED NULL（V87.001・団体戦の子のみ・01 §B.6）")
    void matchesHasBoardNumberColumn() throws Exception {
        assertThat(columnExists("matches", "board_number"))
                .as("matches.board_number が存在すること（V87.001）").isTrue();
        assertThat(columnType("matches", "board_number").toLowerCase())
                .as("board_number は smallint unsigned").contains("smallint").contains("unsigned");
        assertThat(columnIsNullable("matches", "board_number"))
                .as("board_number は NULL 許容（個人戦・団体戦の親は NULL）").isTrue();
    }

    @Test
    @DisplayName("matches の自己参照 FK は parent_match_id→matches(CASCADE) のみ（同一ドメイン・原則2・V87.001）")
    void matchesSelfReferenceForeignKey() throws Exception {
        List<String[]> fks = foreignKeys("matches");
        // V87.001 で自己参照 FK が 1 件だけ張られる（参照先は同一 matches テーブル＝同一ドメイン・原則1/2）。
        assertThat(fks)
                .as("matches の FK 参照先は自身（matches）のみ＝自己参照（クロスドメイン FK 禁止・原則1）")
                .isNotEmpty()
                .allSatisfy(fk -> assertThat(fk[1]).isEqualTo("matches"));
        assertThat(fks)
                .as("自己参照 FK は ON DELETE CASCADE（親団体戦の物理削除で子ボードも消える・原則2・§B.1 注記）")
                .anySatisfy(fk -> {
                    assertThat(fk[1]).isEqualTo("matches");
                    assertThat(fk[2]).isEqualTo("CASCADE");
                });
    }

    // ─── V87.002: match_attachments（局面写真など match スコープ添付・01 §B.7） ───

    @Test
    @DisplayName("match_attachments が作成され主キーが BINARY(16)（UUIDv7・原則6・V87.002）")
    void matchAttachmentsTableExistsWithBinaryPk() throws Exception {
        assertThat(columnExists("match_attachments", "id"))
                .as("match_attachments.id が存在すること（V87.002）").isTrue();
        assertThat(columnType("match_attachments", "id").toLowerCase())
                .as("match_attachments.id は binary(16)（UUIDv7）").isEqualTo("binary(16)");
    }

    @Test
    @DisplayName("match_attachments の列構成（match_id/file_key/content_type/file_size/created_by・§B.7）")
    void matchAttachmentsColumns() throws Exception {
        assertThat(columnExists("match_attachments", "match_id")).isTrue();
        assertThat(columnType("match_attachments", "match_id").toLowerCase()).isEqualTo("binary(16)");
        assertThat(columnExists("match_attachments", "file_key")).isTrue();
        assertThat(columnExists("match_attachments", "content_type")).isTrue();
        assertThat(columnExists("match_attachments", "file_size")).isTrue();
        assertThat(columnExists("match_attachments", "created_by")).isTrue();
        // match_id / content_type / file_size / created_by は NOT NULL（必須メタ）
        assertThat(columnIsNullable("match_attachments", "match_id")).isFalse();
        assertThat(columnIsNullable("match_attachments", "content_type")).isFalse();
        assertThat(columnIsNullable("match_attachments", "file_size")).isFalse();
        assertThat(columnIsNullable("match_attachments", "created_by")).isFalse();
    }

    @Test
    @DisplayName("match_attachments は organization_id / deleted_at を持たない（01 §A.4/§B.7・テナント分離は親 matches）")
    void matchAttachmentsHasNoTenantNorSoftDeleteColumns() throws Exception {
        assertThat(columnExists("match_attachments", "organization_id"))
                .as("match_attachments に organization_id があってはならない（テナント分離は親 matches）").isFalse();
        assertThat(columnExists("match_attachments", "deleted_at"))
                .as("match_attachments に deleted_at があってはならない（親 matches の削除に従う）").isFalse();
    }

    @Test
    @DisplayName("match_attachments の FK は match_id→matches(CASCADE) のみ（同一ドメイン・原則1/2・V87.002）")
    void matchAttachmentsForeignKeys() throws Exception {
        List<String[]> fks = foreignKeys("match_attachments");
        assertThat(fks)
                .as("match_attachments の FK 参照先は matches のみ（クロスドメイン FK 禁止・原則1）")
                .isNotEmpty()
                .allSatisfy(fk -> assertThat(fk[1]).isEqualTo("matches"));
        assertThat(fks)
                .as("match_id→matches は ON DELETE CASCADE（親 matches の削除で添付も消える・原則2）")
                .anySatisfy(fk -> {
                    assertThat(fk[1]).isEqualTo("matches");
                    assertThat(fk[2]).isEqualTo("CASCADE");
                });
    }

    // ─── V89.001: 採点競技（SCORED）対応で本戦スコア列を INT UNSIGNED へ拡張 ───

    @Test
    @DisplayName("matches.home_score/away_score は INT UNSIGNED NULL（V89.001・採点競技の合計点×1000・§B.1.2/§D.8）")
    void matchesScoreColumnsAreIntUnsigned() throws Exception {
        for (String col : List.of("home_score", "away_score")) {
            assertThat(columnExists("matches", col))
                    .as("matches.%s が存在すること", col).isTrue();
            assertThat(columnType("matches", col).toLowerCase())
                    .as("%s は int unsigned（SMALLINT→INT 拡張・採点競技の整数スケール×1000 合計点を格納）", col)
                    .contains("int").contains("unsigned")
                    .doesNotContain("smallint").doesNotContain("bigint");
            assertThat(columnIsNullable("matches", col))
                    .as("%s は NULL 許容（未確定許容・後方互換）", col).isTrue();
        }
    }

    @Test
    @DisplayName("matches.home_penalty_score/away_penalty_score は SMALLINT UNSIGNED 据え置き（PK 戦・採点競技は使わない）")
    void matchesPenaltyScoreColumnsStaySmallint() throws Exception {
        for (String col : List.of("home_penalty_score", "away_penalty_score")) {
            assertThat(columnType("matches", col).toLowerCase())
                    .as("%s は smallint unsigned 据え置き（V89.001 で拡張しない）", col)
                    .contains("smallint").contains("unsigned");
        }
    }
}
