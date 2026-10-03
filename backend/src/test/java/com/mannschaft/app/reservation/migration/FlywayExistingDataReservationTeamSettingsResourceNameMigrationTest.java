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
 * <b>既存の reservation_team_settings 行（resource_name_type / resource_name_custom 列を持たない
 * ALTER 前データ）を持つ MySQL に対し、V146.20260708032335（呼称チーム設定化・F03.4.5 §5.1）を含む
 * 全マイグレーションがクラッシュせず最後まで成功し、既存行が DEFAULT / NULL に後方互換フォールバック
 * すること</b>を検証する番人テスト（受け入れ条件 N-2）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>V146 は {@code reservation_team_settings} へ {@code resource_name_type VARCHAR(10) NOT NULL
 * DEFAULT 'DEFAULT'} と {@code resource_name_custom VARCHAR(30) NULL} を追加する ALTER のみであり、
 * 既存行の backfill UPDATE は行わない（DB DEFAULT による自動充足のみ）。
 * from-scratch 番人（空 DB）では {@code reservation_team_settings} が 0 行のため、
 * 「既存行を持つ環境で DEFAULT 充足が実際に機能するか」を素通りしてしまう。
 * 本テストは <b>V146 直前（V145.20260707153053）まで適用 → ALTER 前スキーマの
 * reservation_team_settings 行をシード → 残り（V146 含む）を適用</b> という既存データ経路を
 * 再現し、既存データ環境での回帰を恒久的に検知する。</p>
 *
 * <p>reservation_team_settings のクロスドメインFK（team_id→teams）は元々存在しない
 * （V108.001 時点でインデックスのみ）ため、親行を用意せず行を直接シードできる。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayExistingDataReservationTeamSettingsResourceNameMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ reservation_team_settings 呼称カラム追加（V146）番人テスト")
class FlywayExistingDataReservationTeamSettingsResourceNameMigrationTest {

    /** V146 の直前バージョン（reservation 関連の現行最大 V145）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V146_TARGET = "145.20260707153053";

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
     * 既存データ経路: V145 まで適用 → resource_name_type/custom 列の無い team_settings 行をシード
     * → 残り（V146 含む）を適用し、クラッシュせず列が追加され既存行が DEFAULT/NULL になることを検証する。
     */
    @Test
    @DisplayName("既存team_settings行_呼称列なし状態からV146適用_クラッシュせず既存行がDEFAULT_NULLになる")
    void 既存データを持つDBでV146が安全に適用される() throws Exception {
        // given: V146 の直前（V145）まで適用
        //  ＝ reservation_team_settings は resource_name_type / resource_name_custom 列を持たない状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V146_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V145 までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // sanity: この時点では resource_name_type 列は存在しない（旧スキーマであることの担保）
            assertThat(columnExists(conn, "reservation_team_settings", "resource_name_type"))
                    .as("V145 時点では resource_name_type 列が存在しないこと").isFalse();

            // ALTER 前スキーマのまま team_settings 行をシードする（allow_public_reservation のみ）。
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(insertTeamSetting(1, 100, true));
                st.executeUpdate(insertTeamSetting(2, 200, false));
            }
        }

        // when: 残りのマイグレーション（V146 含む）を適用する。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、列が追加され、既存行が DEFAULT / NULL に後方互換フォールバックしている
        assertThat(restResult.success).as("V146 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            assertThat(columnExists(conn, "reservation_team_settings", "resource_name_type"))
                    .as("resource_name_type 列が追加されていること").isTrue();
            assertThat(columnExists(conn, "reservation_team_settings", "resource_name_custom"))
                    .as("resource_name_custom 列が追加されていること").isTrue();

            // resource_name_type は NOT NULL
            assertThat(isNullable(conn, "reservation_team_settings", "resource_name_type"))
                    .as("resource_name_type は NOT NULL であること").isFalse();
            // resource_name_custom は NULL 許容
            assertThat(isNullable(conn, "reservation_team_settings", "resource_name_custom"))
                    .as("resource_name_custom は NULL 許容であること").isTrue();

            // 既存 2 行が全て resource_name_type='DEFAULT' / resource_name_custom IS NULL にフォールバック
            assertThat(countRows(conn,
                    "SELECT COUNT(*) FROM reservation_team_settings "
                            + "WHERE resource_name_type = 'DEFAULT' AND resource_name_custom IS NULL"))
                    .as("既存行が DEFAULT / NULL に後方互換フォールバックしていること").isEqualTo(2);

            // allow_public_reservation は既存値を保持している（無関係カラムの巻き添え変更が無いこと）
            assertThat(countRows(conn,
                    "SELECT COUNT(*) FROM reservation_team_settings WHERE team_id = 100 AND allow_public_reservation = TRUE"))
                    .as("既存の allow_public_reservation 値が保持されていること").isEqualTo(1);
        }
    }

    /** ALTER 前スキーマ（resource_name 列なし）で reservation_team_settings へ 1 行 INSERT する SQL を組み立てる。 */
    private static String insertTeamSetting(long id, long teamId, boolean allowPublicReservation) {
        return "INSERT INTO reservation_team_settings "
                + "(id, team_id, allow_public_reservation, created_at, updated_at) VALUES ("
                + id + ", " + teamId + ", " + allowPublicReservation + ", NOW(), NOW())";
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

    private static long countRows(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
