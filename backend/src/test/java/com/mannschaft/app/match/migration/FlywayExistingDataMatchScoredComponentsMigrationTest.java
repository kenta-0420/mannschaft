package com.mannschaft.app.match.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>既存データ（V76 で作られた matches に投入済みの SCORED 試合行＝採点競技）を持つ MySQL に対し、
 * V92.001（match_scored_components 子表の CREATE）を含む全マイグレーションがクラッシュせず適用でき、
 * 既存 SCORED 行が無傷であること、かつ新規採点競技に内訳行を CASCADE 付きで挿入できる</b>ことを
 * 検証する番人テスト（F08.10 SCORED 後段 Phase / sports/07_scored.md §4B / 01 §B.1.2）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>{@code MatchSchemaFlywayTest}（空 DB の from-scratch 構造検証）では matches が 0 行のため
 * 「match_scored_components の CREATE が既存 matches 行を壊さないか」を検知できない
 * （feedback_flyway_existing_data_check_drop の盲点）。本テストは
 * <b>V92.001 直前まで適用 → 採点競技（FIGURE_SKATING）試合行を合計点付きでシード → 残り（V92.001 含む）を適用</b>
 * という既存データ経路を再現し、新規子表の CREATE が既存データ（既存の合計点 home/away_score）を破壊しないこと、
 * および FK CASCADE の実効を恒久的に検証する。</p>
 *
 * <h2>方針</h2>
 * <p>Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（テストは骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.match.migration.FlywayExistingDataMatchScoredComponentsMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ match_scored_components 子表 CREATE（V92.001）番人テスト")
class FlywayExistingDataMatchScoredComponentsMigrationTest {

    /** V92.001 の直前バージョン（origin/main 全体最大 V91 系の最終）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V92_001_TARGET = "91.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_scored_components")
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
    void startContainer() {
        MYSQL.start();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @Test
    @DisplayName("既存SCORED行を持つDBにV92.001適用_内訳表作成_既存合計点無傷_内訳挿入可_CASCADE実効")
    void 既存データを持つDBでV92_001が安全に適用される() throws Exception {
        // given: V92.001 の直前（V91.001）まで適用 ＝ matches は存在するが match_scored_components はまだ無い
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V92_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V91.001 までの適用が成功すること").isTrue();

        UUID figureMatchId = UUID.randomUUID();
        try (Connection c = conn()) {
            // sanity: この時点では match_scored_components テーブルが存在しない（旧スキーマの証明）
            assertThat(tableExists(c, "match_scored_components"))
                    .as("V91.001 時点では match_scored_components テーブルが存在しないこと").isFalse();

            // 既存 SCORED 試合行を合計点（整数スケール×1000・MVP 直接入力相当）付きでシード
            // state_model=SCORED は V85.001 で追加済・score 列は V89.001 で INT UNSIGNED 拡張済
            insertScoredMatch(c, figureMatchId, 215430, 198450);
            assertThat(countMatches(c)).as("シードした SCORED 行が 1 件存在すること").isEqualTo(1);
        }

        // when: 残りのマイグレーション（V92.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V92.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: match_scored_components テーブルが作成された
            assertThat(tableExists(c, "match_scored_components"))
                    .as("V92.001 で match_scored_components が作成されること").isTrue();

            // then-2: 既存 SCORED 行は無傷（子表 CREATE は matches に触れない・既存合計点が保持される）
            assertThat(countMatches(c)).as("既存 SCORED 行が CREATE 後も生存していること").isEqualTo(1);
            assertThat(scoreOf(c, figureMatchId, "home_score"))
                    .as("既存 home_score（合計点×1000）が無傷であること").isEqualTo(215430);
            assertThat(scoreOf(c, figureMatchId, "away_score"))
                    .as("既存 away_score（合計点×1000）が無傷であること").isEqualTo(198450);

            // then-3: 既存 SCORED 試合に内訳行（HOME: TES+PCS−DEDUCTION）を挿入できる
            insertComponent(c, UUID.randomUUID(), figureMatchId, "HOME", "SP", "TES", 88430);
            insertComponent(c, UUID.randomUUID(), figureMatchId, "HOME", "SP", "PCS", 90000);
            insertComponent(c, UUID.randomUUID(), figureMatchId, "HOME", "SP", "DEDUCTION", 1000);
            assertThat(countComponents(c, figureMatchId))
                    .as("採点競技試合に内訳 3 行を挿入できること").isEqualTo(3);

            // then-4: apparatus / competitor_side / score_entry_id / judge_label は NULL 許容（種目区別なし内訳）
            insertComponentNullable(c, UUID.randomUUID(), figureMatchId, "TES", 50000);
            assertThat(countComponents(c, figureMatchId))
                    .as("NULL 列を伴う内訳行も挿入できること（apparatus/side/judge_label NULL）").isEqualTo(4);

            // then-5: FK CASCADE 実効 ＝ 親 match を物理削除すると子 match_scored_components も消える
            deleteMatchPhysically(c, figureMatchId);
            assertThat(countComponents(c, figureMatchId))
                    .as("親 matches 物理削除で子 match_scored_components が CASCADE 削除されること").isZero();
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private void insertScoredMatch(Connection c, UUID id, int homeScore, int awayScore) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO matches
                    (id, organization_id, team_id, sport, kind, status,
                     state_model, home_away, has_scorekeeper, created_by, home_score, away_score)
                VALUES (?, 1, 1, 'FIGURE_SKATING', 'FRIENDLY', 'IN_PROGRESS', 'SCORED', 'HOME', FALSE, 1, ?, ?)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setInt(2, homeScore);
            ps.setInt(3, awayScore);
            ps.executeUpdate();
        }
    }

    private void insertComponent(Connection c, UUID id, UUID matchId, String side, String apparatus,
                                 String componentType, int pointsScaled) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO match_scored_components
                    (id, match_id, competitor_side, apparatus, component_type, points_scaled)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(matchId));
            ps.setString(3, side);
            ps.setString(4, apparatus);
            ps.setString(5, componentType);
            ps.setInt(6, pointsScaled);
            ps.executeUpdate();
        }
    }

    /** competitor_side / apparatus / judge_label / score_entry_id を NULL にした内訳行（NULL 許容の証明）。 */
    private void insertComponentNullable(Connection c, UUID id, UUID matchId,
                                         String componentType, int pointsScaled) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO match_scored_components
                    (id, match_id, component_type, points_scaled)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(matchId));
            ps.setString(3, componentType);
            ps.setInt(4, pointsScaled);
            ps.executeUpdate();
        }
    }

    private void deleteMatchPhysically(Connection c, UUID matchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM matches WHERE id = ?")) {
            ps.setBytes(1, toBytes(matchId));
            ps.executeUpdate();
        }
    }

    private static long countMatches(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM matches")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long scoreOf(Connection c, UUID matchId, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM matches WHERE id = ?")) {
            ps.setBytes(1, toBytes(matchId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countComponents(Connection c, UUID matchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM match_scored_components WHERE match_id = ?")) {
            ps.setBytes(1, toBytes(matchId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (hi >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (lo >>> (8 * (7 - i)));
        }
        return bytes;
    }
}
