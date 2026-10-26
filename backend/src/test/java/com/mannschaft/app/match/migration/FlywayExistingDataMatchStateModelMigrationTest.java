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
 * <b>既存データ（V76 で作られた matches/match_events に投入済みの SOCCER 試合行）を持つ MySQL に対し、
 * V85.001（matches に state_model 列追加＋match_events.period NULL 許容化）を含む全マイグレーションが
 * クラッシュせず適用でき、既存 SOCCER 行が state_model='CONTINUOUS_TIME' で後方互換に充填され、
 * period が NULL 許容に緩和される</b>ことを検証する番人テスト（F08.10 6-① / 01 §D.6 / §B.1 / §B.2）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>{@code FlywayFromScratchMigrationTest}（空 DB 番人）では matches/match_events が 0 行のため
 * 「既存 SOCCER 行が ALTER 後に DEFAULT で充填されるか」「period NULL 化が既存行を壊さないか」を
 * 検知できない（feedback_flyway_existing_data_check_drop の盲点）。本テストは
 * <b>V85.001 直前まで適用 → SOCCER 試合行＋period 付き event 行をシード（旧スキーマが実効）→
 * 残り（V85.001 含む）を適用</b>という既存データ経路を再現し、後方互換の破綻を恒久的に検知する。</p>
 *
 * <h2>方針</h2>
 * <p>Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（テストは骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.match.migration.FlywayExistingDataMatchStateModelMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ matches state_model 追加（V85.001）番人テスト")
class FlywayExistingDataMatchStateModelMigrationTest {

    /** V85.001 の直前バージョン（origin/main 全体最大 V84 系の最終）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V85_001_TARGET = "84.003";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_match_state_model")
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
    @DisplayName("既存SOCCER行を持つDBにV85.001適用_state_modelがCONTINUOUS_TIMEで充填されperiodがNULL許容化")
    void 既存データを持つDBでV85_001が安全に適用される() throws Exception {
        // given: V85.001 の直前（V84.003）まで適用 ＝ matches に state_model 列なし・match_events.period は NOT NULL
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V85_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V84.003 までの適用が成功すること").isTrue();

        UUID matchId = UUID.randomUUID();
        try (Connection c = conn()) {
            // sanity: この時点では state_model 列が存在しない（旧スキーマの証明）
            assertThat(columnExists(c, "matches", "state_model"))
                    .as("V84.003 時点では matches.state_model 列が存在しないこと")
                    .isFalse();
            // sanity: period は NOT NULL（旧スキーマの証明）
            assertThat(columnIsNullable(c, "match_events", "period"))
                    .as("V84.003 時点では match_events.period が NOT NULL であること")
                    .isFalse();

            // 既存 SOCCER 試合行をシード（state_model 列がまだ無いため指定しない）
            insertSoccerMatch(c, matchId);
            // period 付きの既存 event 行（連続時間制）をシード
            insertEventWithPeriod(c, UUID.randomUUID(), matchId, "FIRST_HALF");
            assertThat(countMatches(c)).as("シードした SOCCER 行が 1 件存在すること").isEqualTo(1);
        }

        // when: 残りのマイグレーション（V85.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V85.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 既存 SOCCER 行は生存している
            assertThat(countMatches(c)).as("既存 SOCCER 行が ALTER 後も生存していること").isEqualTo(1);

            // then-2: 既存 SOCCER 行の state_model が DEFAULT 'CONTINUOUS_TIME' で充填される（後方互換）
            assertThat(stateModelOf(c, matchId))
                    .as("既存 SOCCER 行は state_model='CONTINUOUS_TIME' で充填される（SOCCER=CONTINUOUS_TIME と整合）")
                    .isEqualTo("CONTINUOUS_TIME");

            // then-3: state_model 列は NOT NULL（DEFAULT 'CONTINUOUS_TIME'）
            assertThat(columnExists(c, "matches", "state_model")).isTrue();
            assertThat(columnIsNullable(c, "matches", "state_model"))
                    .as("state_model は NOT NULL（DEFAULT 充填）であること").isFalse();

            // then-4: match_events.period が NULL 許容に緩和された
            assertThat(columnIsNullable(c, "match_events", "period"))
                    .as("V85.001 適用後 match_events.period が NULL 許容になること").isTrue();

            // then-5: ターン制相当の event（period=NULL）が INSERT できる（NULL 化の実効確認）
            insertEventWithPeriod(c, UUID.randomUUID(), matchId, null);

            // then-6: 既存 period 付き行は値を保持している（NULL 化は緩和のみで既存値を壊さない）
            assertThat(countEventsWithPeriod(c, "FIRST_HALF"))
                    .as("既存 FIRST_HALF event 行の period 値が保持されていること").isEqualTo(1);
            assertThat(countEventsWithNullPeriod(c))
                    .as("ターン制相当の period=NULL event が 1 件挿入できること").isEqualTo(1);

            // then-7: 新規にセット制（VOLLEYBALL）/ターン制（SHOGI）の matches も state_model 明示で INSERT できる
            insertMatchWithStateModel(c, UUID.randomUUID(), "VOLLEYBALL", "SET_BASED");
            insertMatchWithStateModel(c, UUID.randomUUID(), "SHOGI", "TURN_BASED");
            assertThat(countMatches(c)).as("セット制/ターン制 matches を追加し計 3 件になること").isEqualTo(3);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    /** state_model 列なしで SOCCER 試合行を 1 件 INSERT（NOT NULL 列を充足）。 */
    private void insertSoccerMatch(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO matches
                    (id, organization_id, team_id, sport, kind, status,
                     home_away, has_scorekeeper, created_by)
                VALUES (?, 1, 1, 'SOCCER', 'FRIENDLY', 'SCHEDULED', 'HOME', FALSE, 1)
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.executeUpdate();
        }
    }

    /** state_model 明示で matches 行を INSERT（V85.001 適用後の新規行）。 */
    private void insertMatchWithStateModel(Connection c, UUID id, String sport, String stateModel)
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

    /** match_events 行を period 指定（NULL 可）で INSERT。 */
    private void insertEventWithPeriod(Connection c, UUID id, UUID matchId, String period)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO match_events
                    (id, match_id, period, event_type, team_side)
                VALUES (?, ?, ?, 'GOAL', 'HOME')
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setBytes(2, toBytes(matchId));
            if (period == null) {
                ps.setNull(3, java.sql.Types.VARCHAR);
            } else {
                ps.setString(3, period);
            }
            ps.executeUpdate();
        }
    }

    private static String stateModelOf(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT state_model FROM matches WHERE id = ?")) {
            ps.setBytes(1, toBytes(id));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static long countMatches(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM matches")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long countEventsWithPeriod(Connection c, String period) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM match_events WHERE period = ?")) {
            ps.setString(1, period);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countEventsWithNullPeriod(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM match_events WHERE period IS NULL")) {
            rs.next();
            return rs.getLong(1);
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

    private static boolean columnIsNullable(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return "YES".equalsIgnoreCase(rs.getString(1));
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
