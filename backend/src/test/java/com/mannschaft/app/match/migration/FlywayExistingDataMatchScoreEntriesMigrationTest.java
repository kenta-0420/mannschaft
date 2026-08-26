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
 * V93.001（match_score_entries 子表の CREATE）を含む全マイグレーションがクラッシュせず適用でき、
 * 既存 SCORED 行が無傷であること、かつ新規採点競技に多人数順位エントリ行を CASCADE 付きで挿入できる</b>ことを
 * 検証する番人テスト（F08.10 SCORED 後段 Phase 多人数順位制 / sports/07_scored.md §5B / 01 §B.1.2）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>{@code MatchSchemaFlywayTest}（空 DB の from-scratch 構造検証）では matches が 0 行のため
 * 「match_score_entries の CREATE が既存 matches 行を壊さないか」を検知できない
 * （feedback_flyway_existing_data_check_drop の盲点）。本テストは
 * <b>V93.001 直前まで適用 → 採点競技（FIGURE_SKATING）試合行を合計点付きでシード → 残り（V93.001 含む）を適用</b>
 * という既存データ経路を再現し、新規子表の CREATE が既存データ（既存の合計点 home/away_score）を破壊しないこと、
 * および FK CASCADE の実効を恒久的に検証する。</p>
 *
 * <h2>方針</h2>
 * <p>Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（テストは骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.match.migration.FlywayExistingDataMatchScoreEntriesMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ match_score_entries 子表 CREATE（V93.001）番人テスト")
class FlywayExistingDataMatchScoreEntriesMigrationTest {

    /** V93.001 の直前バージョン（origin/main 全体最大 V92 系の最終）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V93_001_TARGET = "92.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_score_entries")
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
    @DisplayName("既存SCORED行を持つDBにV93.001適用_エントリ表作成_既存合計点無傷_エントリ挿入可_CASCADE実効")
    void 既存データを持つDBでV93_001が安全に適用される() throws Exception {
        // given: V93.001 の直前（V92.001）まで適用 ＝ matches は存在するが match_score_entries はまだ無い
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V93_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V92.001 までの適用が成功すること").isTrue();

        UUID figureMatchId = UUID.randomUUID();
        try (Connection c = conn()) {
            // sanity: この時点では match_score_entries テーブルが存在しない（旧スキーマの証明）
            assertThat(tableExists(c, "match_score_entries"))
                    .as("V92.001 時点では match_score_entries テーブルが存在しないこと").isFalse();

            // 既存 SCORED 試合行を合計点（整数スケール×1000・MVP 直接入力相当）付きでシード
            insertScoredMatch(c, figureMatchId, 215430, 198450);
            assertThat(countMatches(c)).as("シードした SCORED 行が 1 件存在すること").isEqualTo(1);
        }

        // when: 残りのマイグレーション（V93.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V93.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: match_score_entries テーブルが作成された
            assertThat(tableExists(c, "match_score_entries"))
                    .as("V93.001 で match_score_entries が作成されること").isTrue();

            // then-2: 既存 SCORED 行は無傷（子表 CREATE は matches に触れない・既存合計点が保持される）
            assertThat(countMatches(c)).as("既存 SCORED 行が CREATE 後も生存していること").isEqualTo(1);
            assertThat(scoreOf(c, figureMatchId, "home_score"))
                    .as("既存 home_score（合計点×1000）が無傷であること").isEqualTo(215430);
            assertThat(scoreOf(c, figureMatchId, "away_score"))
                    .as("既存 away_score（合計点×1000）が無傷であること").isEqualTo(198450);

            // then-3: 既存 SCORED 試合に多人数順位エントリ行（登録選手・未登録名）を挿入できる
            insertEntry(c, UUID.randomUUID(), figureMatchId, 1001L, null, 210000, 1);
            insertEntry(c, UUID.randomUUID(), figureMatchId, null, "山田 花子", 195000, 2);
            insertEntry(c, UUID.randomUUID(), figureMatchId, 1002L, null, 180000, 3);
            assertThat(countEntries(c, figureMatchId))
                    .as("採点競技試合に出場者エントリ 3 行を挿入できること").isEqualTo(3);

            // then-4: competitor_* / rank_position は NULL 許容（順位算出前・団体・未登録の各組み合わせ）
            insertEntryNullable(c, UUID.randomUUID(), figureMatchId, 50000);
            assertThat(countEntries(c, figureMatchId))
                    .as("NULL 列を伴うエントリ行も挿入できること（competitor_*/rank_position NULL）").isEqualTo(4);

            // then-5: FK CASCADE 実効 ＝ 親 match を物理削除すると子 match_score_entries も消える
            deleteMatchPhysically(c, figureMatchId);
            assertThat(countEntries(c, figureMatchId))
                    .as("親 matches 物理削除で子 match_score_entries が CASCADE 削除されること").isZero();
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

    private void insertEntry(Connection c, UUID id, UUID matchId, Long userId, String name,
                             int totalScaled, int rankPosition) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO match_score_entries
                    (id, match_id, competitor_user_id, competitor_name, total_scaled, rank_position)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(matchId));
            if (userId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, userId);
            }
            ps.setString(4, name);
            ps.setInt(5, totalScaled);
            ps.setInt(6, rankPosition);
            ps.executeUpdate();
        }
    }

    /** competitor_* / rank_position をすべて NULL にしたエントリ行（NULL 許容の証明）。 */
    private void insertEntryNullable(Connection c, UUID id, UUID matchId, int totalScaled) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO match_score_entries
                    (id, match_id, total_scaled)
                VALUES (?, ?, ?)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(matchId));
            ps.setInt(3, totalScaled);
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

    private static long countEntries(Connection c, UUID matchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM match_score_entries WHERE match_id = ?")) {
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
