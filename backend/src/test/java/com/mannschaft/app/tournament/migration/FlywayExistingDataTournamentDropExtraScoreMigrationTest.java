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
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>既存データ（延長別スコアを持つ tournament_matches 行）を持つ MySQL に対し、
 * V90.001（tournament_matches.home_extra_score / away_extra_score の DROP COLUMN）を含む
 * 全マイグレーションがクラッシュせず適用でき、既存行の本戦スコア（home_score/away_score）が
 * 無傷で保持される</b>ことを検証する番人テスト（F08.10 Phase5b-3 / 05 §H.1 移行表）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>{@code FlywayFromScratchMigrationTest}（空 DB 番人）では tournament_matches が 0 行のため
 * 「既存の延長別スコア行が DROP COLUMN で壊れないか／本戦スコアが無傷か」を検知できない
 * （feedback_flyway_existing_data_check_drop の盲点）。本テストは
 * <b>V90.001 直前まで適用 → 延長別スコア入りの行をシード（旧スキーマ＝延長別列が実在）→
 * 残り（V90.001 含む）を適用</b> という既存データ経路を再現し、列削除の後方互換破綻を恒久検知する。</p>
 *
 * <h2>方針</h2>
 * <p>Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で
 * 直接実行する。tournament_matches は matchday/participants への FK を持つため、行シードは
 * {@code SET FOREIGN_KEY_CHECKS=0} で FK 充足を省略する（本テストの関心は列削除の安全性のみ）。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（テストは骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.tournament.migration.FlywayExistingDataTournamentDropExtraScoreMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ tournament_matches 延長別スコア列 DROP（V90.001）番人テスト")
class FlywayExistingDataTournamentDropExtraScoreMigrationTest {

    /** V90.001 の直前バージョン（origin/main 全体最大 V89 系の最終）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V90_001_TARGET = "89.001";

    private static final long FIXTURE_ID = 9001L;

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_tn_drop_extra")
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
    @DisplayName("延長別スコア入りの既存行を持つDBにV90.001適用_延長別列が消え本戦スコアは無傷で保持される")
    void 既存データを持つDBでV90_001が安全に適用される() throws Exception {
        // given: V90.001 の直前（V89.001）まで適用 ＝ tournament_matches に延長別列が実在
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V90_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V89.001 までの適用が成功すること").isTrue();

        try (Connection c = conn()) {
            // sanity: この時点では延長別列が実在する（旧スキーマの証明）
            assertThat(columnExists(c, "tournament_matches", "home_extra_score"))
                    .as("V89.001 時点では home_extra_score 列が実在すること").isTrue();
            assertThat(columnExists(c, "tournament_matches", "away_extra_score"))
                    .as("V89.001 時点では away_extra_score 列が実在すること").isTrue();

            // 既存の対戦カード行を「本戦 3-2 ＋ 延長別 1-0」でシード（FK は本テストの関心外ゆえ省略）。
            try (Statement st = c.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS=0");
                st.executeUpdate(
                        "INSERT INTO tournament_matches "
                                + "(id, matchday_id, home_score, away_score, "
                                + "home_extra_score, away_extra_score, result, leg, version, status, "
                                + "created_at, updated_at) VALUES "
                                + "(" + FIXTURE_ID + ", 1, 3, 2, 1, 0, 'HOME_WIN', 1, 0, 'COMPLETED', "
                                + "NOW(), NOW())");
                st.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        }

        // when: 残りのマイグレーション（V90.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V90.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 延長別列が消えている
            assertThat(columnExists(c, "tournament_matches", "home_extra_score"))
                    .as("V90.001 適用後 home_extra_score 列が削除されていること").isFalse();
            assertThat(columnExists(c, "tournament_matches", "away_extra_score"))
                    .as("V90.001 適用後 away_extra_score 列が削除されていること").isFalse();

            // then-2: 既存行は生存し本戦スコアが無傷（延長別の削除は本戦スコアに影響しない）
            assertThat(scoreOf(c, "home_score"))
                    .as("既存行の home_score=3 が無傷で保持されること").isEqualTo(3L);
            assertThat(scoreOf(c, "away_score"))
                    .as("既存行の away_score=2 が無傷で保持されること").isEqualTo(2L);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private static long scoreOf(Connection c, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM tournament_matches WHERE id = ?")) {
            ps.setLong(1, FIXTURE_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
                return rs.getLong(1) > 0;
            }
        }
    }
}
