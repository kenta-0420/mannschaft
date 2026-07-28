package com.mannschaft.app.village.migration;

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
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>#2357（村寄合の候補日に任意の時刻）DB 移行 番人テスト</b>:
 * {@code V159.__meetup_candidate_time.sql}（Expand + Migrate）を
 * <b>実 MySQL</b>（Testcontainers）で検証する。
 *
 * <h2>なぜモック不可なのか</h2>
 * <p>検証対象は移行 SQL そのもの（列追加・UNIQUE 張り替え・既存データの無損失）であり、
 * いずれも実 RDBMS のセマンティクスでしか再現しない（memory
 * {@code feedback_adapter_mock_ut_false_green_downstream_enum}）。
 * とくに「MySQL の UNIQUE は TIME NULL の重複を許容する」挙動は、DB でしか確認できない。</p>
 *
 * <p>方針は {@code FlywayExistingDataVillageRecruitCategoriesMigrationTest} を踏襲。
 * Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に Flyway を Java API で直接適用する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.village.migration."
        + "FlywayMeetupCandidateTimeMigrationTest#isDockerAvailable")
@DisplayName("Flyway 村寄合 候補日 TIME 追加移行（V159 Expand/Migrate）番人テスト")
class FlywayMeetupCandidateTimeMigrationTest {

    /**
     * V159（本移行）の直前バージョン。ここまで適用してから旧スキーマ（time 列なし）の行をシードする。
     * 本移行の直前は #2359 の V158.20260718115027（module_activation_backfill_grandfather）であり、
     * これは village_meetup 系テーブルに一切触れないため、ここまで適用しても candidate_time 列は存在しない。
     */
    private static final String PRE_V159_TARGET = "158.20260718115027";

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final UUID MEETUP_ID = UUID.randomUUID();
    /** 移行前に投入する「日付のみ」の既存候補日（無損失移行の検証対象）。 */
    private static final UUID LEGACY_CANDIDATE_ID = UUID.randomUUID();

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_meetup_time")
            .withUsername("test")
            .withPassword("test")
            // memory project_testcontainers_mysql_tmpfs_fix: tmpfs 無しは JDBC タイムアウトで落ちる
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
    void startContainerAndMigrate() throws Exception {
        MYSQL.start();

        // given: V159 の直前まで適用 ＝ candidate_time / confirmed_time 列がまだ無い状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V159_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V159 直前までの適用が成功すること").isTrue();

        try (Connection conn = openConn()) {
            // sanity: 旧スキーマの証明
            assertThat(columnExists(conn, "village_meetup_candidate_dates", "candidate_time"))
                    .as("V159 適用前は candidate_time 列が存在しないこと").isFalse();
            assertThat(columnExists(conn, "village_meetups", "confirmed_time"))
                    .as("V159 適用前は confirmed_time 列が存在しないこと").isFalse();

            // 旧スキーマの村・寄合・「日付のみ」候補日をシード
            insertVillage(conn, VILLAGE_ID, "meetup-time", "寄合時刻テスト村");
            insertMeetup(conn, MEETUP_ID, VILLAGE_ID, "夏の寄合");
            insertLegacyCandidate(conn, LEGACY_CANDIDATE_ID, MEETUP_ID, "2026-08-01");
        }

        // when: 残りのマイグレーション（V159 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success)
                .as("V159（Expand/Migrate）を含む残りのマイグレーションが成功すること").isTrue();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    // ==================================================================
    // AC-7（DDL）— 列追加・NULL 許容
    // ==================================================================

    @Test
    @DisplayName("AC-7 candidate_time / confirmed_time が追加され、いずれも NULL 許容（終日=NULL）")
    void 時刻列が追加されNULL許容である() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(columnExists(conn, "village_meetup_candidate_dates", "candidate_time"))
                    .as("candidate_time 列が追加されていること").isTrue();
            assertThat(isNullable(conn, "village_meetup_candidate_dates", "candidate_time"))
                    .as("終日候補のため candidate_time は NULL 許容").isTrue();
            assertThat(columnType(conn, "village_meetup_candidate_dates", "candidate_time"))
                    .as("TIME 型であること").isEqualTo("time");

            assertThat(columnExists(conn, "village_meetups", "confirmed_time"))
                    .as("confirmed_time 列が追加されていること").isTrue();
            assertThat(isNullable(conn, "village_meetups", "confirmed_time"))
                    .as("終日確定のため confirmed_time は NULL 許容").isTrue();
        }
    }

    // ==================================================================
    // AC-8 — 既存「日付のみ」データの無損失移行
    // ==================================================================

    @Test
    @DisplayName("AC-8 移行前に存在した『日付のみ』候補日は candidate_time=NULL で無損失に残る")
    void 既存の日付のみ候補は無損失で残る() throws Exception {
        try (Connection conn = openConn()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT candidate_date, candidate_time FROM village_meetup_candidate_dates "
                                 + "WHERE id = " + bin(LEGACY_CANDIDATE_ID))) {
                assertThat(rs.next()).as("既存の候補日行が残っていること").isTrue();
                assertThat(rs.getString("candidate_date")).isEqualTo("2026-08-01");
                assertThat(rs.getObject("candidate_time"))
                        .as("既存の日付のみ候補は candidate_time=NULL（終日）で移行される").isNull();
            }
        }
    }

    // ==================================================================
    // AC-4（DB）— UNIQUE 張り替え：同日別時刻の共存
    // ==================================================================

    @Test
    @DisplayName("AC-4(DB) 新 UNIQUE (meetup_id, candidate_date, candidate_time) により同日別時刻が共存できる")
    void 同日別時刻が共存できる() throws Exception {
        try (Connection conn = openConn()) {
            insertCandidate(conn, UUID.randomUUID(), MEETUP_ID, "2026-08-10", "10:00:00");
            // 同じ日でも時刻が違えば別レコードとして共存できる（旧 UNIQUE では衝突していた）
            insertCandidate(conn, UUID.randomUUID(), MEETUP_ID, "2026-08-10", "19:00:00");

            assertThat(countScalar(conn,
                    "SELECT COUNT(*) FROM village_meetup_candidate_dates "
                            + "WHERE meetup_id = " + bin(MEETUP_ID) + " AND candidate_date = '2026-08-10'"))
                    .as("同日別時刻の 2 行が共存すること").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("AC-4(DB) 同一 (date, time) の重複は UNIQUE 違反で拒否される")
    void 同一date_timeの重複はDB制約で拒否される() throws Exception {
        try (Connection conn = openConn()) {
            insertCandidate(conn, UUID.randomUUID(), MEETUP_ID, "2026-08-11", "10:00:00");
            // insertCandidate は SQLException を RuntimeException にラップして投げる（他の非例外呼び出し箇所を
            // throws Exception 汚染させないため）。よって実際に飛ぶのは RuntimeException で、原因(cause)が
            // UNIQUE 違反 SQLIntegrityConstraintViolationException であることまで縛って堅牢に検証する。
            assertThatThrownBy(() ->
                    insertCandidate(conn, UUID.randomUUID(), MEETUP_ID, "2026-08-11", "10:00:00"))
                    .as("同一 (meetup_id, date, time) は uk_vmcd_meetup_date_time 違反")
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    @Test
    @DisplayName("AC-4(DB/罠) 終日候補（time=NULL）同士は MySQL UNIQUE では重複を許容する→アプリ層検査が必須")
    void 終日候補の重複はDBでは許容される罠() throws Exception {
        try (Connection conn = openConn()) {
            insertCandidate(conn, UUID.randomUUID(), MEETUP_ID, "2026-08-12", null);
            // MySQL は NULL != NULL 扱いのため、(date, NULL) の重複は UNIQUE で弾けない。
            // これがアプリ層 (VillageMeetupService) で (date, time) ペア重複検査を行う根拠。
            insertCandidate(conn, UUID.randomUUID(), MEETUP_ID, "2026-08-12", null);

            assertThat(countScalar(conn,
                    "SELECT COUNT(*) FROM village_meetup_candidate_dates "
                            + "WHERE meetup_id = " + bin(MEETUP_ID) + " AND candidate_date = '2026-08-12' "
                            + "AND candidate_time IS NULL"))
                    .as("DB は終日候補の重複を許容してしまう（アプリ層で弾く責務）").isEqualTo(2);
        }
    }

    // ==================================================================
    // ヘルパー
    // ==================================================================

    private static Connection openConn() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static String bin(UUID id) {
        return "UNHEX('" + id.toString().replace("-", "") + "')";
    }

    private static void insertVillage(Connection conn, UUID id, String slug, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO villages (id, slug, name, type, join_policy, visibility, "
                        + "created_at, updated_at) "
                        + "VALUES (UNHEX(?), ?, ?, 'COMMUNITY', 'FREE', 'PUBLIC', NOW(6), NOW(6))")) {
            ps.setString(1, id.toString().replace("-", ""));
            ps.setString(2, slug);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private static void insertMeetup(Connection conn, UUID id, UUID villageId, String title) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO village_meetups (id, village_id, title, organizer_user_id, status, "
                        + "created_at, updated_at, version) "
                        + "VALUES (UNHEX(?), UNHEX(?), ?, 1, 'PLANNING', NOW(6), NOW(6), 0)")) {
            ps.setString(1, id.toString().replace("-", ""));
            ps.setString(2, villageId.toString().replace("-", ""));
            ps.setString(3, title);
            ps.executeUpdate();
        }
    }

    /** 旧スキーマ（candidate_time 列なし）の候補日を 1 行 INSERT する。 */
    private static void insertLegacyCandidate(Connection conn, UUID id, UUID meetupId, String date)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO village_meetup_candidate_dates (id, meetup_id, candidate_date, sort_order, created_at) "
                        + "VALUES (UNHEX(?), UNHEX(?), ?, 0, NOW(6))")) {
            ps.setString(1, id.toString().replace("-", ""));
            ps.setString(2, meetupId.toString().replace("-", ""));
            ps.setString(3, date);
            ps.executeUpdate();
        }
    }

    /** 新スキーマ（candidate_time 列あり）の候補日を 1 行 INSERT する。time は NULL 可。 */
    private static void insertCandidate(Connection conn, UUID id, UUID meetupId, String date, String time) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO village_meetup_candidate_dates "
                        + "(id, meetup_id, candidate_date, candidate_time, sort_order, created_at) "
                        + "VALUES (UNHEX(?), UNHEX(?), ?, ?, 0, NOW(6))")) {
            ps.setString(1, id.toString().replace("-", ""));
            ps.setString(2, meetupId.toString().replace("-", ""));
            ps.setString(3, date);
            ps.setString(4, time); // null は setString(4, null) で TIME NULL になる
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static long countScalar(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        return countScalar(conn,
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                        + "AND COLUMN_NAME = '" + column + "'") > 0;
    }

    private static boolean isNullable(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " が存在すること").isTrue();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }

    private static String columnType(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " が存在すること").isTrue();
            return rs.getString(1);
        }
    }
}
