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
 * <b>line_id / template_id 列を持たない既存の予約スロット行を持つ MySQL に対し、
 * V142（reservation_slot_templates 新設＋reservation_slots への ALTER）を含む全マイグレーションが
 * クラッシュせず最後まで成功し、既存行が NULL 充足（後方互換フォールバック=共通枠/手動枠）で通過すること</b>
 * を検証する番人テスト（F03.4.2 §7・F-11 の DB 層）。
 *
 * <h2>このテストが守る不変条件</h2>
 * <ul>
 *   <li>{@code line_id}/{@code template_id} は NULL 許容・デフォルトなしで追加され、既存行は NULL のまま</li>
 *   <li>冪等 UNIQUE {@code uq_rs_template_cell (template_id, slot_date, start_time)} は
 *       <b>NULL 行（手動枠）を制約しない</b> — 同一日・同一開始時刻の手動枠 2 行が共存できる
 *       （MySQL の UNIQUE は NULL を distinct 扱い・§5.3）</li>
 *   <li>{@code reservation_slot_templates} テーブル（UUIDv7 = BINARY(16) PK）が作成される</li>
 * </ul>
 *
 * <p>{@code FlywayExistingDataReservationSlotCapacityMigrationTest} の写経
 * （親 §7 の機能B ALTER と同じ作法）。Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayExistingDataReservationSlotLineTemplateMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ reservation_slots line_id/template_id 追加（V142）番人テスト")
class FlywayExistingDataReservationSlotLineTemplateMigrationTest {

    /** V142 の直前バージョン（観測時点の main 最大）。ここまで適用してから旧スキーマの slot 行をシードする。 */
    private static final String PRE_V142_TARGET = "140.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_line_tpl")
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
    @DisplayName("既存slot行_line_id列なしからV142適用_クラッシュせず既存行NULL・uq_rs_template_cellはNULL行を制約しない")
    void 既存データを持つDBでV142が安全に適用される() throws Exception {
        // given: V142 の直前まで適用 ＝ reservation_slots に line_id / template_id 列が無い状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V142_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V140.001 までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // sanity: この時点で line_id / template_id 列は存在しない（旧スキーマであることの担保）
            assertThat(columnExists(conn, "reservation_slots", "line_id"))
                    .as("V142 前は line_id 列が存在しないこと").isFalse();
            assertThat(columnExists(conn, "reservation_slots", "template_id"))
                    .as("V142 前は template_id 列が存在しないこと").isFalse();

            // 旧スキーマの slot 行をシードする（論理削除済みも含む）
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(insertSlot(1, 100, false));
                st.executeUpdate(insertSlot(2, 100, false));
                st.executeUpdate(insertSlot(3, 200, true));
            }
        }

        // when: 残りのマイグレーション（V142 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、列・テーブル・制約が期待どおり
        assertThat(restResult.success).as("V142 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // 列の追加（両方 NULL 許容）
            assertThat(columnExists(conn, "reservation_slots", "line_id")).isTrue();
            assertThat(isNullable(conn, "reservation_slots", "line_id"))
                    .as("line_id は NULL 許容（NULL=共通枠=既存互換）").isTrue();
            assertThat(columnExists(conn, "reservation_slots", "template_id")).isTrue();
            assertThat(isNullable(conn, "reservation_slots", "template_id"))
                    .as("template_id は NULL 許容（NULL=手動枠）").isTrue();

            // 既存行は NULL のまま（backfill しない宣言・§3.1）
            assertThat(countSlotsWhere(conn, "line_id IS NULL AND template_id IS NULL"))
                    .as("既存 3 行（論理削除含む）が全て NULL 充足で通過すること").isEqualTo(3);

            // 新テーブル reservation_slot_templates（BINARY(16) PK）
            assertThat(tableExists(conn, "reservation_slot_templates"))
                    .as("reservation_slot_templates テーブルが作成されること").isTrue();
            assertThat(columnType(conn, "reservation_slot_templates", "id").toLowerCase())
                    .as("PK は UUIDv7 = BINARY(16)").startsWith("binary(16)");
            assertThat(columnType(conn, "reservation_slot_templates", "day_of_week").toLowerCase())
                    .as("day_of_week は VARCHAR(3)（business_hours と完全同一表現）").startsWith("varchar(3)");

            // UNIQUE uq_rs_template_cell が存在する
            assertThat(indexExists(conn, "reservation_slots", "uq_rs_template_cell"))
                    .as("冪等 UNIQUE uq_rs_template_cell が作成されること").isTrue();
            assertThat(indexExists(conn, "reservation_slots", "idx_rs_team_date_line"))
                    .as("ライン軸グリッド用 idx_rs_team_date_line が作成されること").isTrue();

            // NULL 行（手動枠）は UNIQUE の対象外: 同一 (slot_date, start_time) の手動枠 2 行が共存できる
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(insertManualSlotPostMigration(10, 300));
                st.executeUpdate(insertManualSlotPostMigration(11, 300)); // 同一日・同一時刻でも成功すること
            }
            assertThat(countSlotsWhere(conn, "team_id = 300"))
                    .as("template_id NULL の手動枠は同一セルでも重複制約されないこと").isEqualTo(2);
        }
    }

    /** 旧スキーマ（line_id/template_id 無し・capacity 有り）の reservation_slots へ 1 行 INSERT する SQL。 */
    private static String insertSlot(long id, long teamId, boolean softDeleted) {
        String deletedAt = softDeleted ? "NOW()" : "NULL";
        return "INSERT INTO reservation_slots "
                + "(id, team_id, slot_date, start_time, end_time, booked_count, capacity, slot_status, "
                + " is_exception, created_at, updated_at, deleted_at) VALUES ("
                + id + ", " + teamId + ", '2026-07-01', '10:00:00', '11:00:00', 0, 1, 'AVAILABLE', "
                + "FALSE, NOW(), NOW(), " + deletedAt + ")";
    }

    /**
     * <b>全マイグレーション適用後</b>のスキーマへ、template_id NULL（手動枠）の行を同一セルで INSERT する SQL。
     *
     * <p>この INSERT だけは {@code rest.migrate()}（＝最新版まで適用）の<b>後</b>に実行されるため、
     * 列一覧は「V142 時点」ではなく<b>最新スキーマ</b>に従う必要がある。
     * {@code is_exception} は V174 で撤去済みのため指定しない
     * （上の {@link #insertSlot} は V140.001 時点で実行されるので当時存在した列のままで正しい）。</p>
     */
    private static String insertManualSlotPostMigration(long id, long teamId) {
        return "INSERT INTO reservation_slots "
                + "(id, team_id, slot_date, end_date, start_time, end_time, booked_count, capacity, slot_status, "
                + " created_at, updated_at) VALUES ("
                + id + ", " + teamId + ", '2026-08-01', '2026-08-01', '10:00:00', '10:30:00', 0, 1, 'AVAILABLE', "
                + "NOW(), NOW())";
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

    private static boolean tableExists(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'")) {
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

    private static long countSlotsWhere(Connection conn, String where) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM reservation_slots WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
