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
 * <b>既存の reservation_policies 行（pending_expire_hours 列を持たない ALTER 前データ）を持つ MySQL に対し、
 * V170（仮押さえ自動失効のチーム設定追加・F03.4.5 §6.3）を含む全マイグレーションがクラッシュせず
 * 最後まで成功し、<u>既存行が DB DEFAULT の 24 で充足される</u>こと</b>を検証する番人テスト
 * （受け入れ条件 AC-6-2）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>設計書 §6.3 は「NULL = 自動失効しない／DB DEFAULT 24 で<b>新規・既存とも</b>既定 24 時間」を
 * マスター確定事項としている。V170 は backfill UPDATE を書かず、MySQL の
 * {@code ADD COLUMN ... NULL DEFAULT 24} が既存行を DEFAULT で埋める挙動に依存している。
 * この依存は from-scratch 番人（空 DB）では {@code reservation_policies} が 0 行のため素通りする。</p>
 *
 * <p><b>もし既存行が NULL で埋まる（＝自動失効が既存チームで一切効かない）なら本テストが赤くなる。</b>
 * その場合は V170 に明示的な backfill UPDATE を足して根治すること — 「既存チームでは黙って無効」は
 * 設計意図と乖離した静かな機能欠落であり、隠してはならない。</p>
 *
 * <p>本テストは <b>V170 直前まで適用 → ALTER 前スキーマの reservation_policies 行をシード →
 * 残り（V170 含む）を適用</b> という既存データ経路を再現する。
 * {@code reservation_policies} のクロスドメイン FK（team_id→teams）は存在しない（アーキ原則1）ため、
 * 親行を用意せず行を直接シードできる。Docker 未起動環境では {@code @EnabledIf} でスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration."
        + "FlywayExistingDataReservationPoliciesPendingExpireMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ reservation_policies.pending_expire_hours 追加（V170）番人テスト")
class FlywayExistingDataReservationPoliciesPendingExpireMigrationTest {

    /**
     * V170 の直前まで適用するためのターゲット。
     *
     * <p>「V169 系の全マイグレーションを含み V170 を含まない」上限として、minor 部に十分大きな値を置く。
     * V169.x の兄弟マイグレーションが後から増えても追従できるよう、特定ファイル名を固定しない。</p>
     */
    private static final String PRE_V170_TARGET = "169.99999999999999";

    /** 期待する既存行の充足値（設計書 §6.3 マスター確定「既定 24 時間」）。 */
    private static final int EXPECTED_DEFAULT_HOURS = 24;

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
    @DisplayName("既存policies行_pending_expire列なし状態からV170適用_既存行が既定24で充足される")
    void 既存データを持つDBでV170が安全に適用される() throws Exception {
        // given: V170 の直前（V169 系まで）を適用 ＝ pending_expire_hours 列を持たない状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V170_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V169 系までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // sanity: この時点では pending_expire_hours 列は存在しない（旧スキーマであることの担保）
            assertThat(columnExists(conn, "reservation_policies", "pending_expire_hours"))
                    .as("V170 適用前は pending_expire_hours 列が存在しないこと").isFalse();

            // ALTER 前スキーマのまま reservation_policies 行をシードする
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(insertPolicy(101, "AUTO", 24, "24,1"));
                st.executeUpdate(insertPolicy(102, "MANUAL", 48, "72,24,1"));
            }
        }

        // when: 残りのマイグレーション（V170 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、列が追加され、既存行が DEFAULT 24 で充足されている
        assertThat(restResult.success).as("V170 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            assertThat(columnExists(conn, "reservation_policies", "pending_expire_hours"))
                    .as("pending_expire_hours 列が追加されていること").isTrue();
            assertThat(isNullable(conn, "reservation_policies", "pending_expire_hours"))
                    .as("NULL 許容であること（NULL = 自動失効しない を表現できる）").isTrue();
            assertThat(columnDefault(conn, "reservation_policies", "pending_expire_hours"))
                    .as("DB DEFAULT が 24 であること（新規行の既定）")
                    .isEqualTo(String.valueOf(EXPECTED_DEFAULT_HOURS));

            assertThat(countRows(conn,
                    "SELECT COUNT(*) FROM reservation_policies WHERE pending_expire_hours = "
                            + EXPECTED_DEFAULT_HOURS))
                    .as("既存 2 行とも DEFAULT 24 で充足されていること（既存チームでも自動失効が効く）")
                    .isEqualTo(2);
            assertThat(countRows(conn,
                    "SELECT COUNT(*) FROM reservation_policies WHERE pending_expire_hours IS NULL"))
                    .as("既存行が NULL のまま取り残されていないこと")
                    .isZero();

            // 無関係カラムの巻き添え変更が無いこと
            assertThat(countRows(conn,
                    "SELECT COUNT(*) FROM reservation_policies "
                            + "WHERE team_id = 102 AND approval_mode = 'MANUAL' AND cancel_deadline_hours = 48"))
                    .as("既存の他カラム値が保持されていること")
                    .isEqualTo(1);
        }
    }

    /** ALTER 前スキーマ（pending_expire_hours 無し）で reservation_policies へ 1 行 INSERT する SQL。 */
    private static String insertPolicy(long teamId, String approvalMode, int cancelDeadlineHours,
                                       String remindBeforeHours) {
        // id は BINARY(16)（UUIDv7）。テストでは UUID_TO_BIN で任意の値を採る。
        return "INSERT INTO reservation_policies "
                + "(id, team_id, approval_mode, cancel_deadline_hours, remind_before_hours, created_at, updated_at) "
                + "VALUES (UUID_TO_BIN(UUID()), " + teamId + ", '" + approvalMode + "', "
                + cancelDeadlineHours + ", '" + remindBeforeHours + "', NOW(6), NOW(6))";
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

    private static String columnDefault(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
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
