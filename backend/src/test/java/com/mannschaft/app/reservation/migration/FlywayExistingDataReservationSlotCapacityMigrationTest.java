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
 * <b>capacity 列を持たない既存の予約スロット行を持つ MySQL に対し、
 * V140.001（reservation_slots.capacity 追加・NOT NULL DEFAULT 1）を含む全マイグレーションが
 * クラッシュせず最後まで成功し、既存行の capacity が既定 1 へ backfill されること</b>を検証する番人テスト。
 *
 * <h2>このテストが守る不変条件 / 背景（オーバーブッキング根治）</h2>
 * <p>V140.001 は {@code reservation_slots} に {@code capacity INT NOT NULL DEFAULT 1} を ALTER ADD する。
 * from-scratch 番人（空 DB）では reservation_slots が 0 行のため、既存行への DEFAULT 適用が検証されない。
 * 本テストは <b>V138.001 まで適用 → capacity 列が無い状態の slot 行をシード
 * → 残り（V140.001 含む）を適用</b> という既存データ経路を再現し、
 * 「NOT NULL DEFAULT 1 の列追加で既存行が 1（＝従来の 1:1 想定）になる後方互換」を恒久的に番人化する。</p>
 *
 * <p>reservation_slots のクロスドメインFK（team_id→teams / staff_user_id→users）は
 * V95.001 / V103.001 で撤廃済みのため、親行を用意せず slot 行を直接シードできる。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayExistingDataReservationSlotCapacityMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ reservation_slots.capacity 追加（V140.001）番人テスト")
class FlywayExistingDataReservationSlotCapacityMigrationTest {

    /** V140.001 の直前バージョン。ここまで適用してから capacity 列が無い slot 行をシードする。 */
    private static final String PRE_V140_TARGET = "138.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_capacity")
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
    @DisplayName("既存slot行_capacity列なしからV140適用_クラッシュせず列がNOT_NULLかつ全行capacity=1になる")
    void 既存データを持つDBでV140が安全に適用される() throws Exception {
        // given: V140.001 の直前（V138.001）まで適用 ＝ reservation_slots に capacity 列が無い状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V140_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V138.001 までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // sanity: この時点で capacity 列は存在しない（旧スキーマであることの担保）
            assertThat(columnExists(conn, "reservation_slots", "capacity"))
                    .as("V138.001 時点では capacity 列が存在しないこと")
                    .isFalse();

            // capacity 列が無い状態の slot 行をシードする（論理削除済みも含む）。
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(insertSlot(1, 100, false));
                st.executeUpdate(insertSlot(2, 100, false));
                st.executeUpdate(insertSlot(3, 200, true)); // 論理削除済みも backfill 対象
            }
        }

        // when: 残りのマイグレーション（V140.001 含む）を適用する。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、capacity 列が NOT NULL で追加され、既存全行が既定 1 へ backfill されている
        assertThat(restResult.success).as("V140.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            assertThat(columnExists(conn, "reservation_slots", "capacity"))
                    .as("V140.001 適用後は capacity 列が存在すること").isTrue();
            assertThat(isNullable(conn, "reservation_slots", "capacity"))
                    .as("capacity は NOT NULL であること").isFalse();
            assertThat(columnType(conn, "reservation_slots", "capacity").toLowerCase())
                    .as("capacity は INT 型であること").startsWith("int");

            // 既存 3 行（論理削除含む）が全て capacity=1 へ backfill されている
            assertThat(countTotalSlots(conn)).as("シードした slot 行が 3 件存在すること").isEqualTo(3);
            assertThat(countSlotsWithCapacity(conn, 1))
                    .as("既存全行の capacity が 1（＝従来の 1:1 想定）になっていること").isEqualTo(3);
            assertThat(countSlotsWithCapacityNot(conn, 1))
                    .as("capacity が 1 でない行が 0 件であること").isZero();
        }
    }

    /** capacity 列が無い状態の reservation_slots へ最小列で 1 行 INSERT する SQL を組み立てる。 */
    private static String insertSlot(long id, long teamId, boolean softDeleted) {
        String deletedAt = softDeleted ? "NOW()" : "NULL";
        return "INSERT INTO reservation_slots "
                + "(id, team_id, slot_date, start_time, end_time, booked_count, slot_status, "
                + " is_exception, created_at, updated_at, deleted_at) VALUES ("
                + id + ", " + teamId + ", '2026-07-01', '10:00:00', '11:00:00', 0, 'AVAILABLE', "
                + "FALSE, NOW(), NOW(), " + deletedAt + ")";
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

    private static long countTotalSlots(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM reservation_slots")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long countSlotsWithCapacity(Connection conn, int capacity) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM reservation_slots WHERE capacity = " + capacity)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long countSlotsWithCapacityNot(Connection conn, int capacity) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM reservation_slots WHERE capacity <> " + capacity)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
