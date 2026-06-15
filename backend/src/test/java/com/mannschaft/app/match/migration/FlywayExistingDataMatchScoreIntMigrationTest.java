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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>既存データ（V76 で作られた matches に投入済みの小さなスコアを持つ SOCCER 試合行）を持つ MySQL に対し、
 * V89.001（matches.home_score/away_score を SMALLINT UNSIGNED → INT UNSIGNED へ拡張）を含む全マイグレーションが
 * クラッシュせず適用でき、既存 SOCCER 行の小さなスコア（3-2 等）が無傷で保持され、採点競技の整数スケール×1000
 * 合計点（SMALLINT 上限 65535 を超える大きな値）が新たに格納できる</b>ことを検証する番人テスト
 * （F08.10 SCORED-a / 01 §B.1.2 / §D.8 / sports/07_scored.md §4.1）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>{@code MatchSchemaFlywayTest}（空 DB 番人）では matches が 0 行のため「既存スコア行が型拡張で壊れないか」
 * を検知できない（feedback_flyway_existing_data_check_drop の盲点）。本テストは
 * <b>V89.001 直前まで適用 → 小さなスコアの SOCCER 行をシード（旧 SMALLINT 型が実効）→ 残り（V89.001 含む）を適用</b>
 * という既存データ経路を再現し、型拡張の後方互換破綻を恒久的に検知する。</p>
 *
 * <h2>方針</h2>
 * <p>Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（テストは骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.match.migration.FlywayExistingDataMatchScoreIntMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ matches スコア列 INT 拡張（V89.001）番人テスト")
class FlywayExistingDataMatchScoreIntMigrationTest {

    /** V89.001 の直前バージョン（origin/main 全体最大 V88 系の最終）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V89_001_TARGET = "88.001";

    /** 採点競技の整数スケール×1000 合計点の例（SMALLINT UNSIGNED 上限 65535 を超える＝拡張が効いている証明）。 */
    private static final long SCALED_FIGURE_SCORE = 215_430L; // 215.43 → 215430

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_match_score_int")
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
    @DisplayName("既存小スコア行を持つDBにV89.001適用_スコア列がINT_UNSIGNED化され既存値は無傷で大きな採点値も格納可能")
    void 既存データを持つDBでV89_001が安全に適用される() throws Exception {
        // given: V89.001 の直前（V88.001）まで適用 ＝ home_score/away_score は SMALLINT UNSIGNED
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V89_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V88.001 までの適用が成功すること").isTrue();

        UUID soccerId = UUID.randomUUID();
        try (Connection c = conn()) {
            // sanity: この時点では home_score は smallint（旧スキーマの証明）
            assertThat(columnType(c, "matches", "home_score").toLowerCase())
                    .as("V88.001 時点では matches.home_score が smallint であること")
                    .contains("smallint");

            // 既存 SOCCER 試合行を「小さなスコア 3-2」でシード（旧 SMALLINT 型が実効）
            insertSoccerMatchWithScore(c, soccerId, 3, 2);
            assertThat(countMatches(c)).as("シードした SOCCER 行が 1 件存在すること").isEqualTo(1);
        }

        // when: 残りのマイグレーション（V89.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V89.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 既存 SOCCER 行は生存し小さなスコア値が無傷で保持されている（型拡張は緩和のみ・後方互換）
            assertThat(countMatches(c)).as("既存 SOCCER 行が ALTER 後も生存していること").isEqualTo(1);
            assertThat(scoreOf(c, soccerId, "home_score"))
                    .as("既存 SOCCER 行の home_score=3 が無傷で保持されること").isEqualTo(3L);
            assertThat(scoreOf(c, soccerId, "away_score"))
                    .as("既存 SOCCER 行の away_score=2 が無傷で保持されること").isEqualTo(2L);

            // then-2: home_score/away_score が INT UNSIGNED に拡張された
            assertThat(columnType(c, "matches", "home_score").toLowerCase())
                    .as("V89.001 適用後 matches.home_score が int unsigned になること")
                    .contains("int").contains("unsigned").doesNotContain("smallint").doesNotContain("bigint");
            assertThat(columnType(c, "matches", "away_score").toLowerCase())
                    .as("V89.001 適用後 matches.away_score が int unsigned になること")
                    .contains("int").contains("unsigned").doesNotContain("smallint").doesNotContain("bigint");

            // then-3: 採点競技の整数スケール×1000 合計点（SMALLINT 上限 65535 超）が格納できる（拡張の実効確認）
            UUID figId = UUID.randomUUID();
            insertScoredMatchWithScaledScore(c, figId, SCALED_FIGURE_SCORE, 198_450L);
            assertThat(scoreOf(c, figId, "home_score"))
                    .as("採点競技の合計点×1000（215430・旧 SMALLINT 上限超）が格納できること")
                    .isEqualTo(SCALED_FIGURE_SCORE);
            assertThat(scoreOf(c, figId, "away_score")).isEqualTo(198_450L);

            // then-4: PK 戦スコア列は SMALLINT UNSIGNED 据え置き（採点競技は使わない・拡張対象外）
            assertThat(columnType(c, "matches", "home_penalty_score").toLowerCase())
                    .as("home_penalty_score は smallint unsigned 据え置き").contains("smallint");
        }
    }

    // ── helpers ────────────────────────────────────────────────

    /** SOCCER 試合行をスコア指定で INSERT（旧 SMALLINT 型のとき・小さな値）。 */
    private void insertSoccerMatchWithScore(Connection c, UUID id, int home, int away) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO matches
                    (id, organization_id, team_id, sport, kind, status,
                     home_away, home_score, away_score, has_scorekeeper, created_by)
                VALUES (?, 1, 1, 'SOCCER', 'FRIENDLY', 'COMPLETED', 'HOME', ?, ?, FALSE, 1)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setInt(2, home);
            ps.setInt(3, away);
            ps.executeUpdate();
        }
    }

    /** 採点競技（FIGURE_SKATING）試合行を整数スケール合計点で INSERT（V89.001 適用後・大きな値）。 */
    private void insertScoredMatchWithScaledScore(Connection c, UUID id, long home, long away)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO matches
                    (id, organization_id, team_id, sport, kind, status, state_model,
                     home_away, home_score, away_score, has_scorekeeper, created_by)
                VALUES (?, 1, 1, 'FIGURE_SKATING', 'FRIENDLY', 'COMPLETED', 'SCORED', 'HOME', ?, ?, FALSE, 1)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setLong(2, home);
            ps.setLong(3, away);
            ps.executeUpdate();
        }
    }

    private static long scoreOf(Connection c, UUID id, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM matches WHERE id = ?")) {
            ps.setBytes(1, toBytes(id));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countMatches(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM matches");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String columnType(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT column_type FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
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
