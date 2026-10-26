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
 * <b>既存データ（V76 で作られた matches に投入済みの SOCCER/VOLLEYBALL 試合行）を持つ MySQL に対し、
 * V87.001（ターン制＋団体戦 4 列の ALTER ＋ 自己参照 FK）・V87.002（match_attachments CREATE）を含む全
 * マイグレーションがクラッシュせず適用でき、既存行が無傷であること、かつ団体戦の親子 match（自己参照 FK＋
 * CASCADE）と局面写真添付（FK CASCADE）が実効すること</b>を検証する番人テスト
 * （F08.10 6-④a / 01 §B.1 / §B.6 / §B.7）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>{@code MatchSchemaFlywayTest}（空 DB の from-scratch 構造検証）では matches が 0 行のため
 * 「4 列 ALTER ＋ 自己参照 FK が既存 matches 行を壊さないか」を検知できない
 * （feedback_flyway_existing_data_check_drop の盲点）。本テストは
 * <b>V87.001 直前（V86.001）まで適用 → SOCCER/VOLLEYBALL 試合行をシード → 残り（V87.001/V87.002 含む）を適用</b>
 * という既存データ経路を再現し、ALTER が既存行を破壊しないこと・自己参照 FK CASCADE の実効を恒久的に検証する。</p>
 *
 * <h2>方針</h2>
 * <p>Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（テストは骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.match.migration.FlywayExistingDataMatchTurnTeamMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ ターン制＋団体戦 ALTER/自己参照FK（V87.001/V87.002）番人テスト")
class FlywayExistingDataMatchTurnTeamMigrationTest {

    /** V87.001 の直前バージョン（origin/main 全体最大 V86 系の最終）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V87_TARGET = "86.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_match_turn")
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
    @DisplayName("既存SOCCER/VOLLEYBALL行を持つDBにV87適用_既存行無傷_自己参照FK団体戦CASCADE実効_局面写真CASCADE実効")
    void 既存データを持つDBでV87が安全に適用される() throws Exception {
        // given: V87.001 の直前（V86.001）まで適用 ＝ matches は存在するが 4 列はまだ無い
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V87_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V86.001 までの適用が成功すること").isTrue();

        UUID soccerMatchId = UUID.randomUUID();
        UUID volleyMatchId = UUID.randomUUID();
        try (Connection c = conn()) {
            // sanity: この時点では総手数 / parent_match_id 列が存在しない（旧スキーマの証明）
            assertThat(columnExists(c, "matches", "parent_match_id"))
                    .as("V86.001 時点では parent_match_id 列が存在しないこと").isFalse();
            assertThat(tableExists(c, "match_attachments"))
                    .as("V86.001 時点では match_attachments が存在しないこと").isFalse();

            // 既存 SOCCER / VOLLEYBALL 試合行をシード（4 列はまだ無い）
            insertMatch(c, soccerMatchId, "SOCCER", "CONTINUOUS_TIME", null);
            insertMatch(c, volleyMatchId, "VOLLEYBALL", "SET_BASED", null);
            assertThat(countMatches(c)).as("シードした既存 2 行が存在すること").isEqualTo(2);
        }

        // when: 残りのマイグレーション（V87.001/V87.002 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V87.001/V87.002 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 4 列が追加された
            assertThat(columnExists(c, "matches", "total_moves")).isTrue();
            assertThat(columnExists(c, "matches", "win_method")).isTrue();
            assertThat(columnExists(c, "matches", "parent_match_id")).isTrue();
            assertThat(columnExists(c, "matches", "board_number")).isTrue();
            assertThat(tableExists(c, "match_attachments")).isTrue();

            // then-2: 既存 SOCCER/VOLLEYBALL 行は無傷で新列は NULL（後方互換・球技は使わない）
            assertThat(countMatches(c)).as("既存 2 行が ALTER 後も生存していること").isEqualTo(2);
            assertThat(isColumnNull(c, soccerMatchId, "parent_match_id"))
                    .as("既存 SOCCER 行の parent_match_id は NULL").isTrue();
            assertThat(isColumnNull(c, soccerMatchId, "win_method"))
                    .as("既存 SOCCER 行の win_method は NULL").isTrue();

            // then-3: 団体戦の親子 match を作成できる（自己参照 FK・親=NULL / 子=親 ID＋board_number）
            UUID parentId = UUID.randomUUID();
            UUID board1 = UUID.randomUUID();
            UUID board2 = UUID.randomUUID();
            insertMatch(c, parentId, "SHOGI", "TURN_BASED", null);
            insertBoard(c, board1, "SHOGI", parentId, 1);
            insertBoard(c, board2, "SHOGI", parentId, 2);
            // 個人戦の対局結果（1-0＋win_method）を子に入れられる
            updateTurnResult(c, board1, 1, 0, "RESIGNATION", 95);
            assertThat(countChildren(c, parentId)).as("親に子ボード 2 件が紐づくこと").isEqualTo(2);

            // then-4: 存在しない親 ID を parent_match_id に指定すると自己参照 FK で失敗する
            assertThat(insertBoardWithBadParentFails(c))
                    .as("存在しない親 ID は自己参照 FK 違反で弾かれること").isTrue();

            // then-5: 局面写真添付を作成できる（match_attachments・FK）
            insertAttachment(c, UUID.randomUUID(), board1, "image/png", 1024L);
            assertThat(countAttachments(c, board1)).as("局面写真 1 件が紐づくこと").isEqualTo(1);

            // then-6: 自己参照 FK CASCADE 実効 ＝ 親 match を物理削除すると子ボードも消え、
            //         さらに子に紐づく局面写真も（match_attachments の FK CASCADE で）消える
            deleteMatchPhysically(c, parentId);
            assertThat(countChildren(c, parentId))
                    .as("親 matches 物理削除で子ボードが自己参照 CASCADE 削除されること").isZero();
            assertThat(countAttachments(c, board1))
                    .as("子ボード削除に連動して局面写真も CASCADE 削除されること").isZero();
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private void insertMatch(Connection c, UUID id, String sport, String stateModel, UUID parent)
            throws SQLException {
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

    private void insertBoard(Connection c, UUID id, String sport, UUID parent, int boardNumber)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO matches
                    (id, organization_id, team_id, sport, kind, status,
                     state_model, home_away, has_scorekeeper, created_by, parent_match_id, board_number)
                VALUES (?, 1, 1, ?, 'FRIENDLY', 'SCHEDULED', 'TURN_BASED', 'HOME', FALSE, 1, ?, ?)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setString(2, sport);
            ps.setBytes(3, toBytes(parent));
            ps.setInt(4, boardNumber);
            ps.executeUpdate();
        }
    }

    private void updateTurnResult(Connection c, UUID id, int home, int away, String winMethod, int moves)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE matches SET home_score = ?, away_score = ?, win_method = ?, total_moves = ?
                WHERE id = ?
                """)) {
            ps.setInt(1, home);
            ps.setInt(2, away);
            ps.setString(3, winMethod);
            ps.setInt(4, moves);
            ps.setBytes(5, toBytes(id));
            ps.executeUpdate();
        }
    }

    /** 存在しない親 ID を parent_match_id に指定し、自己参照 FK 違反で失敗すれば true。 */
    private boolean insertBoardWithBadParentFails(Connection c) {
        try {
            insertBoard(c, UUID.randomUUID(), "SHOGI", UUID.randomUUID(), 9);
            return false;
        } catch (SQLException expected) {
            return true;
        }
    }

    private void insertAttachment(Connection c, UUID id, UUID matchId, String contentType, long size)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO match_attachments
                    (id, match_id, file_key, content_type, file_size, created_by)
                VALUES (?, ?, ?, ?, ?, 1)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(matchId));
            ps.setString(3, "match/1/" + matchId + "/" + id);
            ps.setString(4, contentType);
            ps.setLong(5, size);
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

    private static long countChildren(Connection c, UUID parentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM matches WHERE parent_match_id = ?")) {
            ps.setBytes(1, toBytes(parentId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countAttachments(Connection c, UUID matchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM match_attachments WHERE match_id = ?")) {
            ps.setBytes(1, toBytes(matchId));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static boolean isColumnNull(Connection c, UUID id, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM matches WHERE id = ?")) {
            ps.setBytes(1, toBytes(id));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                rs.getObject(1);
                return rs.wasNull();
            }
        }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
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
