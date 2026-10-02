package com.mannschaft.app.reservation.migration;

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
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>既存の予約不可枠行（resource_type / resource_id 列を持たない ALTER 前データ）を持つ MySQL に対し、
 * V137.001（reservation_blocked_times への resource_type / resource_id 追加 ＋ idx_bt_lookup）を含む
 * 全マイグレーションがクラッシュせず最後まで成功し、既存行が TEAM / NULL に後方互換フォールバックすること</b>
 * を検証する番人テスト（受け入れ条件 B-8）。
 *
 * <p>from-scratch 番人（空 DB）では reservation_blocked_times が 0 行のため
 * 「既存行が {@code DEFAULT 'TEAM'} で正しく充足されるか」を素通りしてしまう。
 * 本テストは <b>V137 直前まで適用 → resource_type 列が無い状態の blocked_times 行をシード
 * → 残り（V137.001 含む）を適用</b> という既存データ経路を再現し、後方互換を恒久検知する。</p>
 *
 * <p>reservation_blocked_times のクロスドメインFK（team_id→teams / created_by→users）は
 * V95.001 / V103.001 で既に撤廃済みのため、親行を用意せず直接シードできる。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayExistingDataReservationBlockedTimesResourceAxisMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ reservation_blocked_times resource 軸追加（V137.001）番人テスト")
class FlywayExistingDataReservationBlockedTimesResourceAxisMigrationTest {

    /** V137.001 の直前バージョン。ここまで適用してから resource 軸列の無い blocked_times 行をシードする。 */
    private static final String PRE_V137_TARGET = "136.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_existingdata")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
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

    @Test
    @DisplayName("既存blocked_times行_resource列なし状態からV137適用_列追加され既存行がTEAM/NULLになる")
    void 既存データを持つDBでV137が安全に適用される() throws Exception {
        // given: V137.001 の直前まで適用（reservation_blocked_times には resource_type / resource_id が無い）。
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V137_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V137.001 直前までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // sanity: この時点で resource_type 列はまだ存在しない（旧スキーマの担保）。
            assertThat(columnExists(conn, "reservation_blocked_times", "resource_type"))
                    .as("V137.001 直前では resource_type 列が存在しないこと").isFalse();

            // resource 軸列の無い状態で全日ブロック行と部分ブロック行をシードする。
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO reservation_blocked_times "
                        + "(id, team_id, blocked_date, start_time, end_time, reason, created_by, created_at, updated_at) "
                        + "VALUES (1, 100, '2026-07-01', NULL, NULL, '臨時休業', 900, NOW(), NOW())");
                st.executeUpdate("INSERT INTO reservation_blocked_times "
                        + "(id, team_id, blocked_date, start_time, end_time, reason, created_by, created_at, updated_at) "
                        + "VALUES (2, 100, '2026-07-02', '12:00:00', '13:00:00', '昼休憩', 900, NOW(), NOW())");
            }
        }

        // when: 残りのマイグレーション（V137.001 含む）を適用する。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、列が追加され、既存行が TEAM / NULL に後方互換フォールバックしている。
        assertThat(restResult.success).as("V137.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            assertThat(columnExists(conn, "reservation_blocked_times", "resource_type"))
                    .as("resource_type 列が追加されていること").isTrue();
            assertThat(columnExists(conn, "reservation_blocked_times", "resource_id"))
                    .as("resource_id 列が追加されていること").isTrue();

            // resource_type は NOT NULL
            assertThat(isNullable(conn, "reservation_blocked_times", "resource_type"))
                    .as("resource_type は NOT NULL であること").isFalse();

            // 既存 2 行が全て resource_type='TEAM' / resource_id IS NULL にフォールバック
            assertThat(countRows(conn,
                    "SELECT COUNT(*) FROM reservation_blocked_times WHERE resource_type = 'TEAM' AND resource_id IS NULL"))
                    .as("既存行が TEAM / NULL に後方互換フォールバックしていること").isEqualTo(2);

            // idx_bt_lookup が作成されている
            assertThat(indexExists(conn, "reservation_blocked_times", "idx_bt_lookup"))
                    .as("idx_bt_lookup が作成されていること").isTrue();
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private static boolean isNullable(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).isTrue();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }

    private static boolean indexExists(Connection conn, String table, String index) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.STATISTICS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                             + "AND INDEX_NAME = '" + index + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private static long countRows(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
