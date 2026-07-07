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
 * <b>group_id / menu_id / is_group_primary 列を持たない既存の予約行を持つ MySQL に対し、
 * V144（reservations への予約グループ ALTER）を含む全マイグレーションがクラッシュせず成功し、
 * 既存行が {@code group_id=NULL / menu_id=NULL / is_group_primary=TRUE} で充足されること</b>
 * を検証する番人テスト（F03.4.3 §7・既存データ番人）。
 *
 * <h2>このテストが守る不変条件</h2>
 * <ul>
 *   <li>{@code group_id}/{@code menu_id} は NULL 許容・デフォルトなしで追加され、既存行は NULL のまま（単枠=既存互換）</li>
 *   <li>{@code is_group_primary} は NOT NULL DEFAULT TRUE — 既存行は DEFAULT で自動充足され、
 *       一覧・統計の代表行絞り（{@code is_group_primary = TRUE}）追加後も従来と同件数を返す（挙動後退ゼロ）</li>
 *   <li>インデックス {@code idx_rv_group} / {@code idx_rv_user_primary}・FK {@code fk_rv_menu} が作成される</li>
 * </ul>
 *
 * <p>{@code FlywayExistingDataReservationSlotLineTemplateMigrationTest} の写経。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayExistingDataReservationGroupMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ reservations group_id/menu_id/is_group_primary 追加（V144）番人テスト")
class FlywayExistingDataReservationGroupMigrationTest {

    /** V144 の直前バージョン（観測時点の main 最大）。ここまで適用してから旧スキーマの予約行をシードする。 */
    private static final String PRE_V144_TARGET = "143.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_rv_group")
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
    @DisplayName("既存予約行ありのDBでV144が安全に適用され既存行はNULL/TRUEフォールバックで通過する")
    void 既存データを持つDBでV144が安全に適用される() throws Exception {
        // given: V144 の直前まで適用 ＝ reservations に group_id / menu_id / is_group_primary 列が無い状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V144_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V143.001 までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            assertThat(columnExists(conn, "reservations", "group_id"))
                    .as("V144 前は group_id 列が存在しないこと").isFalse();
            assertThat(columnExists(conn, "reservations", "is_group_primary"))
                    .as("V144 前は is_group_primary 列が存在しないこと").isFalse();

            // 旧スキーマの予約行をシードする（論理削除済みも含む・FK 都合で line/slot を先に用意）
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO reservation_lines (id, team_id, name, display_order, is_active, created_at, updated_at) "
                        + "VALUES (901, 900, 'ライン', 1, TRUE, NOW(), NOW())");
                st.executeUpdate("INSERT INTO reservation_slots "
                        + "(id, team_id, slot_date, start_time, end_time, booked_count, capacity, slot_status, is_exception, created_at, updated_at) "
                        + "VALUES (901, 900, '2026-07-01', '10:00:00', '10:30:00', 1, 1, 'FULL', FALSE, NOW(), NOW())");
                st.executeUpdate(insertReservation(801, false));
                st.executeUpdate(insertReservation(802, false));
                st.executeUpdate(insertReservation(803, true));
            }
        }

        // when: 残りのマイグレーション（V144 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、列・FK・インデックス・既存行フォールバックが期待どおり
        assertThat(restResult.success).as("V144 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            assertThat(columnExists(conn, "reservations", "group_id")).isTrue();
            assertThat(isNullable(conn, "reservations", "group_id"))
                    .as("group_id は NULL 許容（NULL=単枠=既存互換）").isTrue();
            assertThat(columnType(conn, "reservations", "group_id").toLowerCase())
                    .as("group_id は BINARY(16)（UUIDv7）").startsWith("binary(16)");
            assertThat(columnExists(conn, "reservations", "menu_id")).isTrue();
            assertThat(isNullable(conn, "reservations", "menu_id")).isTrue();
            assertThat(columnExists(conn, "reservations", "is_group_primary")).isTrue();
            assertThat(isNullable(conn, "reservations", "is_group_primary"))
                    .as("is_group_primary は NOT NULL").isFalse();

            // 既存行（論理削除含む 3 行）は group_id/menu_id NULL・is_group_primary TRUE で充足される
            assertThat(countReservationsWhere(conn,
                    "group_id IS NULL AND menu_id IS NULL AND is_group_primary = TRUE"))
                    .as("既存行が単枠フォールバックで通過すること").isEqualTo(3);
            // 代表行絞り（is_group_primary = TRUE）でも従来と同件数（挙動後退ゼロ）
            assertThat(countReservationsWhere(conn, "is_group_primary = TRUE")).isEqualTo(3);

            assertThat(indexExists(conn, "reservations", "idx_rv_group"))
                    .as("グループ兄弟行取得用 idx_rv_group が作成されること").isTrue();
            assertThat(indexExists(conn, "reservations", "idx_rv_user_primary"))
                    .as("一覧の代表行絞り用 idx_rv_user_primary が作成されること").isTrue();
            assertThat(foreignKeyExists(conn, "reservations", "fk_rv_menu"))
                    .as("menu_id の同一ドメイン FK fk_rv_menu が作成されること").isTrue();
        }
    }

    /** 旧スキーマ（group 系列なし）の reservations へ 1 行 INSERT する SQL。 */
    private static String insertReservation(long id, boolean softDeleted) {
        String deletedAt = softDeleted ? "NOW()" : "NULL";
        return "INSERT INTO reservations "
                + "(id, reservation_slot_id, line_id, team_id, user_id, status, booked_at, created_at, updated_at, deleted_at) "
                + "VALUES (" + id + ", 901, 901, 900, 700, 'CONFIRMED', NOW(), NOW(), NOW(), " + deletedAt + ")";
    }

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

    private static boolean indexExists(Connection conn, String table, String indexName) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.STATISTICS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = '" + table + "' "
                             + "AND INDEX_NAME = '" + indexName + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private static boolean foreignKeyExists(Connection conn, String table, String fkName) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = '" + table + "' "
                             + "AND CONSTRAINT_NAME = '" + fkName + "' "
                             + "AND CONSTRAINT_TYPE = 'FOREIGN KEY'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

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

    private static String columnType(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " が存在すること").isTrue();
            return rs.getString(1);
        }
    }

    private static long countReservationsWhere(Connection conn, String where) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM reservations WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
