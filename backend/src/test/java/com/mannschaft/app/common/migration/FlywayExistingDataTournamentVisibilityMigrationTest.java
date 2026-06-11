package com.mannschaft.app.common.migration;

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
 * <b>既存データ（旧 visibility 値 MEMBERS_ONLY）を持つ MySQL に対し、
 * tournaments.visibility 6 値拡張マイグレーション
 * （{@code V82.001__alter_tournaments_visibility_six_levels.sql}）を含む
 * 全マイグレーションがクラッシュせず最後まで成功すること</b>を検証する番人テスト。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>tournaments.visibility は V8.038 で {@code ENUM('PUBLIC','MEMBERS_ONLY')} として作成された。
 * 6 値拡張マイグレーションは旧 {@code MEMBERS_ONLY} 行を {@code SCOPE_AFFILIATED} へ移行してから
 * ENUM を最終 6 値へ確定する。{@link FlywayFromScratchMigrationTest}（空 DB 番人）では
 * tournaments が 0 行のため UPDATE が 0 行となり「既存データを持つ環境でのみ破綻する」経路を
 * 見逃す（[[feedback_flyway_existing_data_check_drop]]）。本テストは
 * <b>移行直前まで適用 → 旧値 MEMBERS_ONLY をシード → 残りを適用</b> という既存データ経路を
 * 再現し、移行の正しさ（旧値ゼロ・SCOPE_AFFILIATED へ移行・ENUM が新 6 値）を恒久検知する。</p>
 *
 * <h2>方針</h2>
 * <p>{@link FlywayExistingDataTeamVisibilityMigrationTest} の方式を踏襲。Spring コンテキストを
 * 起動せず Testcontainers の実 MySQL 8.0 に Flyway を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataTournamentVisibilityMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ tournament visibility 6 値移行 番人テスト")
class FlywayExistingDataTournamentVisibilityMigrationTest {

    /**
     * 6 値拡張マイグレーション（{@code V82.001}）の直前バージョン。ここまで適用してから旧値をシードする。
     * <p>visibility migration を {@code V9.20260611091634} → {@code V82.001} へリネームしたため、
     * PRE_TARGET も {@code V82.001} 直前の現行最大 versioned migration（{@code V81.001}）へ追従させる。
     * 旧採番（{@code V9.x}）のままだと V10〜V81 を飛ばした早期地点で停止し、repeatable
     * {@code R__seed_visibility_template_presets.sql} が必要テーブル未作成の状態で走って失敗する。
     */
    private static final String PRE_TARGET = "81.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_tn_existingdata")
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
    @DisplayName("旧値MEMBERS_ONLYからの移行_クラッシュせずSCOPE_AFFILIATEDかつ新6値ENUMになる")
    void 既存データを持つDBで6値移行が安全に適用される() throws Exception {
        // given: 6 値拡張の直前まで適用 ＝ tournaments.visibility は ENUM('PUBLIC','MEMBERS_ONLY')
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("直前バージョンまでの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = conn.createStatement()) {

            // sanity: この時点で列は ENUM('PUBLIC','MEMBERS_ONLY')（旧スキーマであることの担保）
            String preType = columnType(conn, "tournaments", "visibility");
            assertThat(preType.toUpperCase())
                    .as("移行前は MEMBERS_ONLY を含む旧 ENUM であること")
                    .contains("MEMBERS_ONLY");

            // FK 充足のため organization を 1 件 seed する。
            // organizations は seed migration を持たないため id=1 は未使用＝衝突しない。
            // public_id は V71.20260609125253 で slug（NOT NULL・VARCHAR(30)・UNIQUE）に置き換え済み。
            st.executeUpdate(
                    "INSERT INTO organizations (id, name, org_type, visibility, hierarchy_visibility, "
                            + "supporter_enabled, version, created_at, updated_at, slug) VALUES "
                            + "(1, 'TN移行組織', 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW(), "
                            + "'t-org-1')");
            // created_by 用ユーザーは V1.012__seed_system_user.sql が id=1 を既に投入済み。
            // ここで insertUser(1, ...) すると users.PRIMARY が重複し
            // SQLIntegrityConstraintViolationException で落ちるため自前 seed はしない
            // （insertTournament は created_by=1 を参照するので FK は充足される）。

            // 旧値 MEMBERS_ONLY と PUBLIC をそれぞれ seed（旧 ENUM が実効しているため
            // ここで旧2値以外を入れようとすると失敗する＝旧スキーマの証明）。
            st.executeUpdate(insertTournament(1, "MEMBERS_ONLY", "OPEN", false));
            st.executeUpdate(insertTournament(2, "PUBLIC", "OPEN", false));
            // 論理削除済みの MEMBERS_ONLY 行も移行対象であることを確認するため seed する
            st.executeUpdate(insertTournament(3, "MEMBERS_ONLY", "COMPLETED", true));
        }

        // when: 残りのマイグレーション（6 値拡張含む）を適用する。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、旧値ゼロ・SCOPE_AFFILIATED へ移行・ENUM が新 6 値
        assertThat(restResult.success).as("6 値拡張を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            assertThat(countByVisibilityRaw(conn, "MEMBERS_ONLY"))
                    .as("MEMBERS_ONLY が 1 件も残っていないこと").isZero();
            // 旧 MEMBERS_ONLY 2 行（論理削除含む）が SCOPE_AFFILIATED へ移行
            assertThat(countByVisibilityRaw(conn, "SCOPE_AFFILIATED"))
                    .as("旧 MEMBERS_ONLY 2 行が SCOPE_AFFILIATED へ移行していること").isEqualTo(2);
            // 元 PUBLIC はそのまま保持
            assertThat(countByVisibilityRaw(conn, "PUBLIC"))
                    .as("元 PUBLIC はそのまま保持されること").isEqualTo(1);

            // 列型が ENUM（新 6 値）になっている
            String columnType = columnType(conn, "tournaments", "visibility");
            assertThat(columnType.toLowerCase())
                    .as("visibility 列が ENUM 型であること").startsWith("enum(");
            assertThat(columnType.toUpperCase())
                    .as("ENUM に新 6 値がすべて含まれること")
                    .contains("PUBLIC")
                    .contains("SUPPORTERS_AND_ABOVE")
                    .contains("MEMBERS_AND_ABOVE")
                    .contains("ADMINS_AND_ABOVE")
                    .contains("SCOPE_AFFILIATED")
                    .contains("PARTICIPANTS_ONLY");
            assertThat(columnType.toUpperCase())
                    .as("旧 MEMBERS_ONLY は ENUM から削除されていること")
                    .doesNotContain("'MEMBERS_ONLY'");
        }
    }

    private static String insertTournament(long id, String visibility, String status, boolean softDeleted) {
        String deletedAt = softDeleted ? "NOW()" : "NULL";
        return "INSERT INTO tournaments ("
                + "id, organization_id, name, format, win_points, draw_points, loss_points, "
                + "has_draw, has_sets, has_extra_time, has_penalties, score_unit_label, "
                + "league_round_type, knockout_legs, visibility, status, version, created_by, "
                + "created_at, updated_at, deleted_at) VALUES ("
                + id + ", 1, 'tn" + id + "', 'LEAGUE', 3, 1, 0, 1, 0, 0, 0, '点', 'SINGLE', 1, '"
                + visibility + "', '" + status + "', 0, 1, NOW(), NOW(), " + deletedAt + ")";
    }

    /** information_schema を介さず visibility の生値で件数を数える（論理削除行も含める）。 */
    private static long countByVisibilityRaw(Connection conn, String value) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM tournaments WHERE visibility = '" + value + "'")) {
            rs.next();
            return rs.getLong(1);
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
}
