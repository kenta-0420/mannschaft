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
 * <b>既存データ（休眠足場 3 列に値が入っている行を含む）を持つ MySQL に対し、
 * V174（reservation_slots の recurrence_rule / parent_slot_id / is_exception 撤去）を含む
 * 全マイグレーションがクラッシュせず最後まで成功し、self-FK と 3 列が消えること</b>を
 * 検証する番人テスト。
 *
 * <h2>このテストが守る不変条件</h2>
 * <p>{@code parent_slot_id} には self-FK（{@code fk_reservation_slots_parent}、ON DELETE RESTRICT）が
 * 張られているため、<b>FK を落とす前に列を落とすと ALTER が失敗する</b>。
 * from-scratch 番人（空 DB）では行が 0 件でも FK 依存は同じだが、
 * 本テストは「3 列に実データが入った既存行」を先にシードしたうえで撤去を通し、
 * 削除順序（FK → 列）と既存データ下での安全性を恒久的に番人化する。</p>
 *
 * <p>reservation_slots のクロスドメイン FK（team_id→teams / staff_user_id→users）は
 * V95.001 / V103.001 で撤廃済みのため、親行を用意せず slot 行を直接シードできる。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration."
        + "FlywayExistingDataReservationSlotDropUnusedColumnsMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ reservation_slots 未使用3列撤去（V174）番人テスト")
class FlywayExistingDataReservationSlotDropUnusedColumnsMigrationTest {

    /** 撤去マイグレーションの直前バージョン。ここまで適用してから休眠足場に値を持つ行をシードする。 */
    private static final String PRE_DROP_TARGET = "173.20260730033807";

    private static final String FK_NAME = "fk_reservation_slots_parent";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_slot_drop")
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
    @DisplayName("休眠足場に値を持つ既存行がある状態でV174適用_self-FKと3列が消え行は残る")
    void 既存データを持つDBで未使用3列の撤去が安全に適用される() throws Exception {
        // given: 撤去マイグレーションの直前まで適用 ＝ 3 列と self-FK が存在する状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_DROP_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("撤去直前までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // sanity: この時点では 3 列と self-FK が存在する（旧スキーマであることの担保）
            assertThat(columnExists(conn, "recurrence_rule")).as("撤去前は recurrence_rule が存在").isTrue();
            assertThat(columnExists(conn, "parent_slot_id")).as("撤去前は parent_slot_id が存在").isTrue();
            assertThat(columnExists(conn, "is_exception")).as("撤去前は is_exception が存在").isTrue();
            assertThat(foreignKeyExists(conn)).as("撤去前は self-FK が存在").isTrue();

            // 休眠足場に実データが入った行をシードする（親行＋子行＋論理削除済み）。
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(insertParentSlot(1, 100));
                st.executeUpdate(insertChildSlot(2, 100, 1));
                st.executeUpdate(insertSoftDeletedChildSlot(3, 200, 1));
            }
        }

        // when: 残りのマイグレーション（V174 含む）を適用する。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、self-FK と 3 列が消え、行そのものは失われていない
        assertThat(restResult.success).as("V174 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            assertThat(foreignKeyExists(conn))
                    .as("self-FK " + FK_NAME + " が撤去されていること").isFalse();
            assertThat(columnExists(conn, "recurrence_rule"))
                    .as("recurrence_rule が撤去されていること").isFalse();
            assertThat(columnExists(conn, "parent_slot_id"))
                    .as("parent_slot_id が撤去されていること").isFalse();
            assertThat(columnExists(conn, "is_exception"))
                    .as("is_exception が撤去されていること").isFalse();

            assertThat(countSlots(conn))
                    .as("既存 slot 行（論理削除含む）が失われていないこと").isEqualTo(3);
        }
    }

    private static String insertParentSlot(long id, long teamId) {
        return "INSERT INTO reservation_slots "
                + "(id, team_id, slot_date, start_time, end_time, booked_count, capacity, slot_status, "
                + " recurrence_rule, parent_slot_id, is_exception, created_at, updated_at) VALUES ("
                + id + ", " + teamId + ", '2026-07-01', '10:00:00', '11:00:00', 0, 1, 'AVAILABLE', "
                + "'{\"freq\":\"WEEKLY\"}', NULL, FALSE, NOW(), NOW())";
    }

    private static String insertChildSlot(long id, long teamId, long parentId) {
        return "INSERT INTO reservation_slots "
                + "(id, team_id, slot_date, start_time, end_time, booked_count, capacity, slot_status, "
                + " recurrence_rule, parent_slot_id, is_exception, created_at, updated_at) VALUES ("
                + id + ", " + teamId + ", '2026-07-08', '10:00:00', '11:00:00', 0, 1, 'AVAILABLE', "
                + "NULL, " + parentId + ", TRUE, NOW(), NOW())";
    }

    private static String insertSoftDeletedChildSlot(long id, long teamId, long parentId) {
        return "INSERT INTO reservation_slots "
                + "(id, team_id, slot_date, start_time, end_time, booked_count, capacity, slot_status, "
                + " recurrence_rule, parent_slot_id, is_exception, created_at, updated_at, deleted_at) VALUES ("
                + id + ", " + teamId + ", '2026-07-15', '10:00:00', '11:00:00', 0, 1, 'AVAILABLE', "
                + "NULL, " + parentId + ", TRUE, NOW(), NOW(), NOW())";
    }

    private static boolean columnExists(Connection conn, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = 'reservation_slots' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private static boolean foreignKeyExists(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = 'reservation_slots' "
                             + "AND CONSTRAINT_TYPE = 'FOREIGN KEY' "
                             + "AND CONSTRAINT_NAME = '" + FK_NAME + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private static long countSlots(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM reservation_slots")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
