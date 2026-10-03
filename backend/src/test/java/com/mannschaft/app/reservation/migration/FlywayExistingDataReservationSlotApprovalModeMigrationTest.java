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
 * <b>既存の予約スロット行（approval_mode が NOT NULL ENUM・初期値 AUTO 等）を持つ MySQL に対し、
 * V112.001（reservation_slots.approval_mode の NULL 許容化 ＋ 既存行 backfill）を含む
 * 全マイグレーションがクラッシュせず最後まで成功すること</b>を検証する番人テスト。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>V112.001 は (1) reservation_policies を新設し、(2) reservation_slots.approval_mode を
 * {@code ENUM('AUTO','MANUAL') NOT NULL DEFAULT 'AUTO'}（V3.151 由来）から
 * {@code ENUM('AUTO','MANUAL') NULL} へ MODIFY し、(3) 既存行を全て NULL へ backfill する。</p>
 *
 * <p>from-scratch 番人（空 DB）では reservation_slots が 0 行のため
 * {@code UPDATE ... SET approval_mode = NULL} が 0 行更新となり、
 * 「既存行を持つ環境でのみ起こりうる backfill の破綻」を素通りしてしまう。
 * 本テストは <b>V111.001 まで適用 → NOT NULL 状態の slot 行をシード
 * → 残り（V112.001 含む）を適用</b> という既存データ経路を再現し、
 * 既存データ環境での回帰を恒久的に検知する。</p>
 *
 * <h2>方針</h2>
 * <p>Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に対して
 * {@link Flyway} を Java API で直接実行する。reservation_slots のクロスドメインFK
 * （team_id→teams / staff_user_id→users / created_by→users）は V95.001 / V103.001 で
 * 既に撤廃済みのため、親行を用意せず slot 行を直接シードできる。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayExistingDataReservationSlotApprovalModeMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ reservation_slots.approval_mode NULL 化（V112.001）番人テスト")
class FlywayExistingDataReservationSlotApprovalModeMigrationTest {

    /** V112.001 の直前バージョン。ここまで適用してから既存 slot 行をシードする。 */
    private static final String PRE_V112_TARGET = "111.001";

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

    /**
     * 既存データ経路: V111.001 まで適用 → NOT NULL ENUM 状態の slot 行をシード
     * → 残り（V112.001 含む）を適用し、クラッシュせず列が NULL 許容になり全行が NULL になることを検証する。
     */
    @Test
    @DisplayName("既存slot行_NOT_NULL状態からV111適用_クラッシュせず列がNULL許容かつ全行NULLになる")
    void 既存データを持つDBでV111が安全に適用される() throws Exception {
        // given: V112.001 の直前（V111.001）まで適用
        //  ＝ reservation_slots.approval_mode は ENUM('AUTO','MANUAL') NOT NULL DEFAULT 'AUTO' の状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V112_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V111.001 までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // sanity: この時点で approval_mode は NOT NULL（旧スキーマであることの担保）
            assertThat(isNullable(conn, "reservation_slots", "approval_mode"))
                    .as("V111.001 時点では approval_mode が NOT NULL であること")
                    .isFalse();

            // NOT NULL 状態のため明示的に値を入れて slot 行をシードする（初期値 AUTO 相当 + MANUAL 混在）。
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(insertSlot(1, 100, "AUTO"));
                st.executeUpdate(insertSlot(2, 100, "AUTO"));
                st.executeUpdate(insertSlot(3, 200, "MANUAL"));
                // 論理削除済み行も backfill 対象であることを確認するためシードする
                st.executeUpdate(insertSlotSoftDeleted(4, 200, "AUTO"));
            }
        }

        // when: 残りのマイグレーション（V112.001 含む）を適用する。
        // approval_mode が NOT NULL のままだと UPDATE ... SET NULL でクラッシュする想定。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、列が NULL 許容になり、全行が NULL へ backfill されている
        assertThat(restResult.success).as("V112.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // 列が NULL 許容に変更されている
            assertThat(isNullable(conn, "reservation_slots", "approval_mode"))
                    .as("V112.001 適用後は approval_mode が NULL 許容になっていること")
                    .isTrue();

            // 既存行（論理削除含む 4 行）が全て NULL へ backfill されている
            assertThat(countTotalSlots(conn))
                    .as("シードした slot 行が 4 件存在すること").isEqualTo(4);
            assertThat(countSlotsWithApprovalModeNotNull(conn))
                    .as("approval_mode が NULL でない行が 0 件であること（全行 backfill 済み）")
                    .isZero();

            // 列型は ENUM のまま（NULL 許容になっただけ）
            String columnType = columnType(conn, "reservation_slots", "approval_mode");
            assertThat(columnType.toLowerCase())
                    .as("approval_mode 列が ENUM 型のままであること").startsWith("enum(");

            // reservation_policies が新設されている
            assertThat(tableExists(conn, "reservation_policies"))
                    .as("reservation_policies テーブルが新設されていること").isTrue();
        }
    }

    /** NOT NULL ENUM 状態の reservation_slots へ最小列で 1 行 INSERT する SQL を組み立てる。 */
    private static String insertSlot(long id, long teamId, String approvalMode) {
        return "INSERT INTO reservation_slots "
                + "(id, team_id, slot_date, start_time, end_time, booked_count, slot_status, "
                + " is_exception, approval_mode, created_at, updated_at) VALUES ("
                + id + ", " + teamId + ", '2026-07-01', '10:00:00', '11:00:00', 0, 'AVAILABLE', "
                + "FALSE, '" + approvalMode + "', NOW(), NOW())";
    }

    /** 論理削除済みの reservation_slots 行を INSERT する SQL を組み立てる。 */
    private static String insertSlotSoftDeleted(long id, long teamId, String approvalMode) {
        return "INSERT INTO reservation_slots "
                + "(id, team_id, slot_date, start_time, end_time, booked_count, slot_status, "
                + " is_exception, approval_mode, created_at, updated_at, deleted_at) VALUES ("
                + id + ", " + teamId + ", '2026-07-01', '12:00:00', '13:00:00', 0, 'AVAILABLE', "
                + "FALSE, '" + approvalMode + "', NOW(), NOW(), NOW())";
    }

    /** 指定テーブル・列が NULL 許容かどうかを返す（information_schema.IS_NULLABLE = 'YES'）。 */
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

    /** 指定テーブル・列の COLUMN_TYPE（例: {@code enum('AUTO','MANUAL')}）を返す。 */
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

    /** reservation_slots の全行数（論理削除含む生の行数）。 */
    private static long countTotalSlots(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM reservation_slots")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** approval_mode が NULL でない reservation_slots 行数（論理削除含む）。 */
    private static long countSlotsWithApprovalModeNotNull(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM reservation_slots WHERE approval_mode IS NOT NULL")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 指定テーブルが現在のスキーマに存在するか。 */
    private static boolean tableExists(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }
}
