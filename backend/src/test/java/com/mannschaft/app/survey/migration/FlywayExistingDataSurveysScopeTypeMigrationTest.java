package com.mannschaft.app.survey.migration;

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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <b>既存データ（汚染値: URLパス語 "teams"/"organizations"）を持つ MySQL に対し、
 * V108（surveys.scope_type の正準化 + ENUM 化）が安全に適用されることを検証する番人テスト。</b>
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>V108 は Step1/Step2 で汚染値 UPDATE を行い、Step3 で ENUM 型に変更する。
 * {@link com.mannschaft.app.common.migration.FlywayFromScratchMigrationTest}（from-scratch 番人）では
 * surveys が 0 行のため UPDATE が 0 行となり、既存データ起因の失敗を見逃す。
 * 本テストは <b>V107.001 まで適用 → 汚染値を含む surveys 行をシード → V108 を適用</b> という
 * 既存データ経路を再現し、V108 が汚染値を正しく正準化してから ENUM 型変換することを検証する。</p>
 *
 * <h2>方針</h2>
 * <p>{@link com.mannschaft.app.common.migration.FlywayExistingDataTeamVisibilityMigrationTest} と同様、
 * Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に対して Flyway を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.survey.migration.FlywayExistingDataSurveysScopeTypeMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ surveys.scope_type 正準化（V108）番人テスト")
class FlywayExistingDataSurveysScopeTypeMigrationTest {

    /** V108 の直前バージョン。ここまで適用してから汚染データをシードする。 */
    private static final String PRE_V108_TARGET = "107.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_existingdata_survey")
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
     * 既存データ経路: V107.001 まで適用 → 汚染値 surveys 行をシード → 残り（V108）を適用し、
     * V108 が安全に汚染値を正準化して ENUM 型に変換することを検証する。
     *
     * <p>検証項目:</p>
     * <ol>
     *   <li>V107.001 までは scope_type が VARCHAR(20) で汚染値を保持できること</li>
     *   <li>V108 適用後: "teams" → "TEAM" へ変換されていること</li>
     *   <li>V108 適用後: "organizations" → "ORGANIZATION" へ変換されていること</li>
     *   <li>V108 適用後: 既存の "TEAM"（正準値）は不変であること</li>
     *   <li>V108 適用後: scope_type 列が ENUM('ORGANIZATION','TEAM') 型になっていること</li>
     *   <li>V108 適用後: ENUM 外の値は INSERT できないこと（DB レベルの防御）</li>
     * </ol>
     */
    @Test
    @DisplayName("汚染値データを持つDBでV108が安全に適用され正準化・ENUM化される")
    void 既存汚染データでV108が安全に適用される() throws Exception {
        // given: V108 の直前（V107.001）まで適用 ＝ surveys.scope_type は VARCHAR(20) の状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V108_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V107.001 までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = conn.createStatement()) {

            // sanity: この時点で scope_type は VARCHAR(20)（ENUM 化前）
            String columnTypeBefore = columnType(conn, "surveys", "scope_type");
            assertThat(columnTypeBefore.toLowerCase())
                    .as("V107.001 時点では scope_type が VARCHAR(20) であること")
                    .contains("varchar");

            // 汚染値（URLパス語 "teams"="56件相当"・"organizations"="19件相当"）と
            // 正準値（"TEAM"="既存3件相当"）をシード。
            // 実際のデータ分布（2026-06-18 確認: teams=56, organizations=19, TEAM=3）を模す。
            st.executeUpdate(insertSurvey(1, "teams"));       // 汚染値: URLパス語
            st.executeUpdate(insertSurvey(2, "teams"));       // 汚染値: URLパス語（複数行代表）
            st.executeUpdate(insertSurvey(3, "organizations")); // 汚染値: URLパス語
            st.executeUpdate(insertSurvey(4, "TEAM"));        // 正準値: 既存正常データ
        }

        // when: 残りのマイグレーション（V108 含む）を適用する。
        // V108 が汚染値 UPDATE を Step1/2 で実施後に ENUM ALTER するため、
        // 汚染値が残ったままだと ENUM 変換でクラッシュする。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: V108 適用が成功する
        assertThat(restResult.success).as("V108 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // ① 汚染値 "teams" が残っていない
            assertThat(countByScopeTypeRaw(conn, "teams"))
                    .as("汚染値 'teams' が残っていないこと").isZero();

            // ② 汚染値 "organizations" が残っていない
            assertThat(countByScopeTypeRaw(conn, "organizations"))
                    .as("汚染値 'organizations' が残っていないこと").isZero();

            // ③ "teams"(2行)が "TEAM" に変換され、既存 "TEAM"(1行) と合わせて計3行
            assertThat(countByScopeTypeRaw(conn, "TEAM"))
                    .as("汚染値 'teams' 2 行 + 既存正準値 'TEAM' 1 行 = 計 3 行になること")
                    .isEqualTo(3L);

            // ④ "organizations"(1行)が "ORGANIZATION" に変換されている
            assertThat(countByScopeTypeRaw(conn, "ORGANIZATION"))
                    .as("汚染値 'organizations' 1 行が 'ORGANIZATION' に変換されていること")
                    .isEqualTo(1L);

            // ⑤ 列型が ENUM になっている
            String columnTypeAfter = columnType(conn, "surveys", "scope_type");
            assertThat(columnTypeAfter.toLowerCase())
                    .as("scope_type 列が ENUM 型に変更されていること").startsWith("enum(");
            assertThat(columnTypeAfter.toUpperCase())
                    .as("ENUM に ORGANIZATION と TEAM が含まれること")
                    .contains("ORGANIZATION")
                    .contains("TEAM");

            // ⑥ ENUM 外の値は INSERT できないこと（DB レベル防御）
            // MySQL 8.0 strict mode では ENUM 外の値挿入が Error 1265 になる
            assertThatCode(() -> {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate(insertSurvey(99, "users"));
                }
            }).as("ENUM 外の値 'users' は INSERT 不可であること")
              .isInstanceOf(Exception.class);
        }
    }

    /**
     * V107.001 時点（surveys が VARCHAR(20) でFKなし）に最小列で 1 行 INSERT する SQL を組み立てる。
     *
     * <p>V106.001 で created_by の FK（fk_surveys_created_by）は撤廃済みのため、
     * created_by は NULL で挿入可能。</p>
     */
    private static String insertSurvey(long id, String scopeType) {
        return "INSERT INTO surveys "
                + "(id, scope_type, scope_id, title, status, is_anonymous, "
                + " allow_multiple_submissions, results_visibility, distribution_mode, "
                + " auto_post_to_timeline, include_supporters, version, created_at, updated_at) VALUES ("
                + id + ", '" + scopeType + "', 1, 'テスト" + id + "', 'DRAFT', 0, "
                + "0, 'AFTER_RESPONSE', 'ALL', "
                + "0, 0, 0, NOW(), NOW())";
    }

    /** surveys テーブルで指定 scope_type の件数を数える（論理削除行も含める）。 */
    private static long countByScopeTypeRaw(Connection conn, String value) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM surveys WHERE scope_type = '" + value + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 指定テーブル・列の COLUMN_TYPE（例: {@code enum('ORGANIZATION','TEAM')}）を返す。 */
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
