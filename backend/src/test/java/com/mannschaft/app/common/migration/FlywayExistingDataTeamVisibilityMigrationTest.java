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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>既存データ（旧 visibility 値 + 旧 CHECK 制約）を持つ MySQL に対し、
 * V79.001（teams.visibility の ENUM 化）を含む全マイグレーションが
 * クラッシュせず最後まで成功すること</b>を検証する番人テスト。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>V79.001 の初版は、V2.004 由来の旧 CHECK 制約
 * {@code chk_teams_visibility CHECK (visibility IN ('PUBLIC','ORGANIZATION_ONLY','PRIVATE'))}
 * を <b>DROP せずに</b> 新値 {@code GUESTS_AND_ABOVE} へ UPDATE していたため、
 * 既存データ（旧値の行）を持つ本番/staging では CHECK 違反（MySQL 3819）でクラッシュした。</p>
 *
 * <p>{@link FlywayFromScratchMigrationTest}（空 DB に対する from-scratch 番人）では、
 * teams が 0 行のため UPDATE が 0 行となり、CHECK 違反に当たらず素通りしてしまう。
 * すなわち「既存データを持つ環境でのみ破綻する」本バグは from-scratch 番人の盲点だった。
 * 本テストは <b>V78.001 まで適用 → 旧値の teams 行をシード（この時点で旧 CHECK は実効）
 * → 残りのマイグレーション（V79.001 含む）を適用</b> という既存データ経路を再現し、
 * 本バグの再発を恒久的に検知する。</p>
 *
 * <h2>方針</h2>
 * <p>{@link FlywayFromScratchMigrationTest} と同様、Spring コンテキストを起動せず
 * Testcontainers の実 MySQL 8.0 に対して {@link Flyway} を Java API で直接実行する
 * （{@code application-test.yml} の {@code flyway.enabled=false} を避けるため）。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataTeamVisibilityMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ team visibility 移行（V79.001）番人テスト")
class FlywayExistingDataTeamVisibilityMigrationTest {

    /** V79.001 の直前バージョン。ここまで適用してから旧データをシードする。 */
    private static final String PRE_V79_TARGET = "78.001";

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
     * 既存データ経路: V78.001 まで適用 → 旧値 teams をシード → 残りを適用し、
     * V79.001 がクラッシュせず全行が新値・列が ENUM になることを検証する。
     */
    @Test
    @DisplayName("旧値データ_旧CHECK状態からV79適用_クラッシュせず全行新値かつENUM列になる")
    void 既存データを持つDBでV79が安全に適用される() throws Exception {
        // given: V79.001 の直前（V78.001）まで適用 ＝ teams は VARCHAR(20) + 旧 CHECK の状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V79_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V78.001 までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = conn.createStatement()) {

            // sanity: この時点で旧 CHECK 制約が存在する（旧スキーマであることの担保）
            assertThat(checkConstraintExists(conn, "chk_teams_visibility"))
                    .as("V78.001 時点では旧 CHECK 制約 chk_teams_visibility が存在すること")
                    .isTrue();

            // 旧値（PUBLIC / ORGANIZATION_ONLY / PRIVATE）の行をシード。
            // ※ 旧 CHECK が実効しているため、ここで旧3値以外を入れようとすると失敗する＝旧スキーマの証明。
            long now = System.currentTimeMillis();
            st.executeUpdate(insertTeam(1, "PUBLIC", false));
            st.executeUpdate(insertTeam(2, "ORGANIZATION_ONLY", false));
            st.executeUpdate(insertTeam(3, "PRIVATE", false));
            // 論理削除済みの旧値行も移行対象であることを確認するためシードする
            st.executeUpdate(insertTeam(4, "PRIVATE", true));
            // 触れておくだけの now（未使用警告回避ではなく将来の拡張余地）
            assertThat(now).isPositive();
        }

        // when: 残りのマイグレーション（V79.001 含む）を適用する。
        // 旧版 V79.001 ならここで CHECK 違反 / FlywayException でクラッシュする。
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 成功し、全行が新値・列が ENUM になっている
        assertThat(restResult.success).as("V79.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // 旧値（ORGANIZATION_ONLY / PRIVATE）が 1 件も残っていない
            assertThat(countByVisibilityRaw(conn, "ORGANIZATION_ONLY"))
                    .as("ORGANIZATION_ONLY が残っていないこと").isZero();
            assertThat(countByVisibilityRaw(conn, "PRIVATE"))
                    .as("PRIVATE が残っていないこと").isZero();

            // 旧 PRIVATE/ORGANIZATION_ONLY だった 3 行（論理削除含む）は GUESTS_AND_ABOVE へ移行
            assertThat(countByVisibilityRaw(conn, "GUESTS_AND_ABOVE"))
                    .as("旧 ORGANIZATION_ONLY/PRIVATE 3 行が GUESTS_AND_ABOVE に移行していること")
                    .isEqualTo(3);
            // 元 PUBLIC はそのまま保持
            assertThat(countByVisibilityRaw(conn, "PUBLIC"))
                    .as("元 PUBLIC はそのまま保持されること").isEqualTo(1);

            // 列型が ENUM（新4値）になっている
            String columnType = columnType(conn, "teams", "visibility");
            assertThat(columnType.toLowerCase())
                    .as("visibility 列が ENUM 型に変更されていること").startsWith("enum(");
            assertThat(columnType.toUpperCase())
                    .as("ENUM に新4値がすべて含まれること")
                    .contains("PUBLIC")
                    .contains("GUESTS_AND_ABOVE")
                    .contains("SUPPORTERS_AND_ABOVE")
                    .contains("MEMBERS_AND_ABOVE");

            // 冗長な旧 CHECK 制約が残っていない（ENUM が許容値を強制するため不要）
            assertThat(checkConstraintExists(conn, "chk_teams_visibility"))
                    .as("V79.001 適用後は旧 CHECK 制約が残っていないこと")
                    .isFalse();
        }
    }

    /** 旧スキーマ（VARCHAR(20)+旧CHECK）の teams へ最小列で 1 行 INSERT する SQL を組み立てる。 */
    private static String insertTeam(long id, String visibility, boolean softDeleted) {
        String deletedAt = softDeleted ? "NOW()" : "NULL";
        // public_id は V77.001 で NOT NULL（default なし・BINARY(16)・UNIQUE）化されたため、
        // 既存データ環境を再現する seed でも一意な値を明示的に与える必要がある。
        return "INSERT INTO teams "
                + "(id, name, public_id, visibility, supporter_enabled, version, "
                + " archived_at, deleted_at, created_at, updated_at) VALUES ("
                + id + ", 'team" + id + "', UUID_TO_BIN(UUID(), 1), '" + visibility + "', 1, 0, "
                + "NULL, " + deletedAt + ", NOW(), NOW())";
    }

    /** information_schema から visibility の値で件数を数える（論理削除行も含める）。 */
    private static long countByVisibilityRaw(Connection conn, String value) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM teams WHERE visibility = '" + value + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 指定テーブル・列の COLUMN_TYPE（例: {@code enum('PUBLIC',...)}）を返す。 */
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

    /** 指定名の CHECK 制約が現在のスキーマに存在するか。 */
    private static boolean checkConstraintExists(Connection conn, String constraintName) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND CONSTRAINT_TYPE = 'CHECK'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names.contains(constraintName);
    }
}
