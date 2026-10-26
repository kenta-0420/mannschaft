package com.mannschaft.app.tournament.migration;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>既存データ（V8.038 で作られた tournaments に投入済みの大会行）を持つ MySQL に対し、
 * V91.001（tournaments に sport 列追加）を含む全マイグレーションがクラッシュせず適用でき、
 * 既存大会が sport='SOCCER' で後方互換に充填される</b>ことを検証する番人テスト
 * （F08.10 多競技対応・🟡-1a）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>{@code FlywayFromScratchMigrationTest}（空 DB 番人）では tournaments が 0 行のため
 * 「既存大会行が ALTER ADD 後に DEFAULT 'SOCCER' で充填されるか」を検知できない
 * （feedback_flyway_existing_data_check_drop の盲点）。本テストは
 * <b>V91.001 直前まで適用 → 大会行をシード（旧スキーマ＝sport 列なし）→ 残り（V91.001 含む）を適用</b>
 * という既存データ経路を再現し、後方互換の破綻を恒久的に検知する。</p>
 *
 * <h2>方針</h2>
 * <p>Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（テストは骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.tournament.migration.FlywayExistingDataTournamentAddSportMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ tournaments sport 列追加（V91.001）番人テスト")
class FlywayExistingDataTournamentAddSportMigrationTest {

    /** V91.001 の直前バージョン（origin/main 全体最大 V90 系の最終）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V91_001_TARGET = "90.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_tn_add_sport")
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
    @DisplayName("既存大会行を持つDBにV91.001適用_sportがSOCCERで充填され新規はVOLLEYBALL等を指定できる")
    void 既存データを持つDBでV91_001が安全に適用される() throws Exception {
        // given: V91.001 の直前（V90.001）まで適用 ＝ tournaments に sport 列なし
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V91_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V90.001 までの適用が成功すること").isTrue();

        long existingTournamentId;
        try (Connection c = conn()) {
            // sanity: この時点では sport 列が存在しない（旧スキーマの証明）
            assertThat(columnExists(c, "tournaments", "sport"))
                    .as("V90.001 時点では tournaments.sport 列が存在しないこと")
                    .isFalse();

            // 既存大会行をシード（sport 列がまだ無いため指定しない）
            existingTournamentId = insertLegacyTournament(c);
            assertThat(countTournaments(c)).as("シードした大会行が 1 件存在すること").isEqualTo(1);
        }

        // when: 残りのマイグレーション（V91.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V91.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 既存大会行は生存している
            assertThat(countTournaments(c)).as("既存大会行が ALTER 後も生存していること").isEqualTo(1);

            // then-2: sport 列が追加され NOT NULL（DEFAULT 'SOCCER'）である
            assertThat(columnExists(c, "tournaments", "sport")).isTrue();
            assertThat(columnIsNullable(c, "tournaments", "sport"))
                    .as("sport は NOT NULL（DEFAULT 充填）であること").isFalse();

            // then-3: 既存大会行の sport が DEFAULT 'SOCCER' で充填される（後方互換）
            assertThat(sportOf(c, existingTournamentId))
                    .as("既存大会は sport='SOCCER' で充填される（従来挙動と一致）")
                    .isEqualTo("SOCCER");

            // then-4: 新規大会は sport を明示（VOLLEYBALL/SHOGI 等）して INSERT できる（多競技対応の実効確認）
            long volleyId = insertTournamentWithSport(c, "VOLLEYBALL");
            long shogiId = insertTournamentWithSport(c, "SHOGI");
            assertThat(sportOf(c, volleyId)).isEqualTo("VOLLEYBALL");
            assertThat(sportOf(c, shogiId)).isEqualTo("SHOGI");
            assertThat(countTournaments(c)).as("多競技大会を追加し計 3 件になること").isEqualTo(3);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    /** sport 列なしで大会行を 1 件 INSERT（NOT NULL 列を充足）。AUTO_INCREMENT の id を返す。 */
    private long insertLegacyTournament(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tournaments"
                        + " (organization_id, name, format, visibility, status, version, created_by)"
                        + " VALUES (1, 'レガシー大会', 'LEAGUE', 'PUBLIC', 'DRAFT', 0, 1)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** sport 明示で大会行を INSERT（V91.001 適用後の新規行）。AUTO_INCREMENT の id を返す。 */
    private long insertTournamentWithSport(Connection c, String sport) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tournaments"
                        + " (organization_id, name, format, sport, visibility, status, version, created_by)"
                        + " VALUES (1, ?, 'LEAGUE', ?, 'PUBLIC', 'DRAFT', 0, 1)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sport + "大会");
            ps.setString(2, sport);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String sportOf(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT sport FROM tournaments WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static long countTournaments(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM tournaments");
             ResultSet rs = ps.executeQuery()) {
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
}
