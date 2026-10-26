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
 * <b>既存データ（V76 で作られた matches に投入済みの SOCCER 試合行）を持つ MySQL に対し、
 * V86.001（match_sets 子表の CREATE）を含む全マイグレーションがクラッシュせず適用でき、既存 SOCCER 行が
 * 無傷であること、かつ新規 VOLLEYBALL 試合に match_sets 行を CASCADE 付きで挿入できる</b>ことを
 * 検証する番人テスト（F08.10 6-③a / 01 §B.5 / sports/04 §4）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>{@code MatchSchemaFlywayTest}（空 DB の from-scratch 構造検証）では matches が 0 行のため
 * 「match_sets の CREATE が既存 matches 行を壊さないか」を検知できない
 * （feedback_flyway_existing_data_check_drop の盲点）。本テストは
 * <b>V86.001 直前まで適用 → SOCCER 試合行をシード（match_sets はまだ無い）→ 残り（V86.001 含む）を適用</b>
 * という既存データ経路を再現し、新規子表の CREATE が既存データを破壊しないこと、および FK CASCADE の実効を
 * 恒久的に検証する。</p>
 *
 * <h2>方針</h2>
 * <p>Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（テストは骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.match.migration.FlywayExistingDataMatchSetsMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ match_sets 子表 CREATE（V86.001）番人テスト")
class FlywayExistingDataMatchSetsMigrationTest {

    /** V86.001 の直前バージョン（origin/main 全体最大 V85 系の最終）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V86_001_TARGET = "85.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_match_sets")
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
    @DisplayName("既存SOCCER行を持つDBにV86.001適用_match_sets作成_既存行無傷_バレーにセット挿入可_CASCADE実効")
    void 既存データを持つDBでV86_001が安全に適用される() throws Exception {
        // given: V86.001 の直前（V85.001）まで適用 ＝ matches は存在するが match_sets はまだ無い
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V86_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V85.001 までの適用が成功すること").isTrue();

        UUID soccerMatchId = UUID.randomUUID();
        try (Connection c = conn()) {
            // sanity: この時点では match_sets テーブルが存在しない（旧スキーマの証明）
            assertThat(tableExists(c, "match_sets"))
                    .as("V85.001 時点では match_sets テーブルが存在しないこと").isFalse();

            // 既存 SOCCER 試合行をシード（state_model は V85.001 で追加済・SOCCER=CONTINUOUS_TIME）
            insertMatch(c, soccerMatchId, "SOCCER", "CONTINUOUS_TIME");
            assertThat(countMatches(c)).as("シードした SOCCER 行が 1 件存在すること").isEqualTo(1);
        }

        // when: 残りのマイグレーション（V86.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V86.001 を含む残りのマイグレーションが成功すること").isTrue();

        UUID volleyMatchId = UUID.randomUUID();
        try (Connection c = conn()) {
            // then-1: match_sets テーブルが作成された
            assertThat(tableExists(c, "match_sets")).as("V86.001 で match_sets が作成されること").isTrue();

            // then-2: 既存 SOCCER 行は無傷（match_sets CREATE は matches に触れない）
            assertThat(countMatches(c)).as("既存 SOCCER 行が CREATE 後も生存していること").isEqualTo(1);

            // then-3: 新規 VOLLEYBALL 試合に match_sets 行を挿入できる
            insertMatch(c, volleyMatchId, "VOLLEYBALL", "SET_BASED");
            insertSet(c, UUID.randomUUID(), volleyMatchId, 1, 25, 23, "HOME", false);
            insertSet(c, UUID.randomUUID(), volleyMatchId, 5, 15, 13, "HOME", true);
            assertThat(countSets(c, volleyMatchId))
                    .as("VOLLEYBALL 試合に match_sets 2 行を挿入できること").isEqualTo(2);

            // then-4: UNIQUE(match_id, set_number) が効く（同一セット番号の重複挿入は失敗）
            assertThat(insertDuplicateSetFails(c, volleyMatchId))
                    .as("UNIQUE(match_id, set_number) により同一セット番号の重複は弾かれること").isTrue();

            // then-5: FK CASCADE 実効 ＝ 親 match を物理削除すると子 match_sets も消える
            deleteMatchPhysically(c, volleyMatchId);
            assertThat(countSets(c, volleyMatchId))
                    .as("親 matches 物理削除で子 match_sets が CASCADE 削除されること").isZero();
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private void insertMatch(Connection c, UUID id, String sport, String stateModel) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO matches
                    (id, organization_id, team_id, sport, kind, status,
                     state_model, home_away, has_scorekeeper, created_by)
                VALUES (?, 1, 1, ?, 'FRIENDLY', 'SCHEDULED', ?, 'HOME', FALSE, 1)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setString(2, sport);
            ps.setString(3, stateModel);
            ps.executeUpdate();
        }
    }

    private void insertSet(Connection c, UUID id, UUID matchId, int setNumber,
                           int homePoints, int awayPoints, String winnerSide, boolean isFinal)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO match_sets
                    (id, match_id, set_number, home_points, away_points, winner_side, is_final_set)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(matchId));
            ps.setInt(3, setNumber);
            ps.setInt(4, homePoints);
            ps.setInt(5, awayPoints);
            ps.setString(6, winnerSide);
            ps.setBoolean(7, isFinal);
            ps.executeUpdate();
        }
    }

    /** 同一 (match_id, set_number) の重複挿入が UNIQUE 制約で失敗すれば true。 */
    private boolean insertDuplicateSetFails(Connection c, UUID matchId) {
        try {
            insertSet(c, UUID.randomUUID(), matchId, 1, 10, 8, "HOME", false);
            return false; // 例外が出なければ UNIQUE が効いていない
        } catch (SQLException expected) {
            return true;
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

    private static long countSets(Connection c, UUID matchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM match_sets WHERE match_id = ?")) {
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
