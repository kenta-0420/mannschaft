package com.mannschaft.app.activity.migration;

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
 * <b>AC-7 既存データ番人テスト</b>:
 * status 列を持たない既存の {@code activity_results} 行を持つ MySQL に対し、
 * V141.001（status 列追加 + PUBLISHED backfill）を含む全マイグレーションが
 * クラッシュせず、<b>既存行がすべて PUBLISHED になる</b>ことを検証する。
 *
 * <p>{@code FlywayFromScratchMigrationTest}（空 DB）では activity_results が 0 行のため
 * backfill が 0 行となり「既存データが下書き扱いで消える」退行を見逃す。本テストは
 * <b>V140.001 まで適用 → status 列がまだ無い状態で activity_results 行をシード →
 * 残りのマイグレーション（V141.001 含む）を適用</b> という既存データ経路を再現する。</p>
 *
 * <p>方針は {@code FlywayExistingDataTeamVisibilityMigrationTest} を踏襲し、Spring コンテキストを
 * 起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.activity.migration.FlywayExistingDataActivityStatusMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ activity_results status 移行（V141.001）番人テスト")
class FlywayExistingDataActivityStatusMigrationTest {

    /** V141.001 の直前バージョン。ここまで適用してから status 列の無い既存行をシードする。 */
    private static final String PRE_V141_TARGET = "140.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_activity_status")
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
    @DisplayName("AC-7 status列の無い既存activity_results行がV141適用後に全てPUBLISHEDになる")
    void 既存activity行がV141適用後に全てPUBLISHED() throws Exception {
        // given: V141.001 の直前（V140.001）まで適用 ＝ activity_results に status 列がまだ無い状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V141_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V140.001 までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = conn.createStatement()) {

            // sanity: この時点では status 列が存在しない（旧スキーマの証明）
            assertThat(columnExists(conn, "activity_results", "status"))
                    .as("V140.001 時点では status 列が存在しないこと").isFalse();

            // 既存行が参照するテンプレートを 1 件シード（created_by=1 = V1.012 のシステムユーザー）
            st.executeUpdate("INSERT INTO activity_templates "
                    + "(id, scope_type, scope_id, name, created_by, created_at, updated_at) "
                    + "VALUES (1, 'TEAM', 100, 'テンプレ', 1, NOW(), NOW())");

            // status 列の無い既存 activity_results 行をシード（通常行 + 論理削除行）
            st.executeUpdate(insertActivity(1, false));
            st.executeUpdate(insertActivity(2, false));
            // 論理削除済み行も backfill 対象であることを確認するためシードする
            st.executeUpdate(insertActivity(3, true));
        }

        // when: 残りのマイグレーション（V141.001 含む）を適用する。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、既存の全行（論理削除含む）が PUBLISHED になっている
        assertThat(restResult.success).as("V141.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // status 列が追加されている
            assertThat(columnExists(conn, "activity_results", "status"))
                    .as("V141.001 適用後は status 列が存在すること").isTrue();

            // 既存 3 行（論理削除含む）が全て PUBLISHED
            assertThat(countByStatus(conn, "PUBLISHED"))
                    .as("既存行はすべて PUBLISHED で backfill されること").isEqualTo(3);
            assertThat(countByStatus(conn, "DRAFT"))
                    .as("既存行に DRAFT は存在しないこと（下書き扱いで消えていない）").isZero();

            // template_id が NULL 許容に緩和されていること（DRAFT 最小作成のため）
            assertThat(isNullable(conn, "activity_results", "template_id"))
                    .as("template_id が NULL 許容に緩和されていること").isTrue();
        }
    }

    /** 旧スキーマ（status 列無し）の activity_results へ最小列で 1 行 INSERT する SQL を組み立てる。 */
    private static String insertActivity(long id, boolean softDeleted) {
        String deletedAt = softDeleted ? "NOW()" : "NULL";
        return "INSERT INTO activity_results "
                + "(id, scope_type, scope_id, template_id, title, activity_date, "
                + " visibility, created_by, created_at, updated_at, deleted_at) VALUES ("
                + id + ", 'TEAM', 100, 1, 'activity" + id + "', '2026-05-04', "
                + "'MEMBERS_ONLY', 1, NOW(), NOW(), " + deletedAt + ")";
    }

    /** status 値ごとの件数（論理削除行も含める）。 */
    private static long countByStatus(Connection conn, String value) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM activity_results WHERE status = '" + value + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 指定テーブル・列が存在するか。 */
    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    /** 指定テーブル・列が NULL 許容か。 */
    private static boolean isNullable(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " が存在すること").isTrue();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }
}
