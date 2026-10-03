package com.mannschaft.app.team.migration;

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
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>クロスドメインFK撤廃 第三陣D（team_templates）の番人テスト。</b>
 *
 * <p>V105.001 で team_templates の「users を親とする ON DELETE SET NULL の監査カラム」FK 1件を撤廃only する:</p>
 * <ul>
 *   <li>{@code team_templates.fk_team_templates_created_by}（created_by → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V105.001 の直前（V104.001）まで適用 → 監査列＝対象 user を持つ子行をシード。</li>
 *   <li>V105.001 直前時点で対象FKが実在することを sanity 確認。</li>
 *   <li>残り（V105.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V105.001 で対象FKが撤廃される。</li>
 *   <li><b>親 users 行（監査列でのみ参照される user）を物理 DELETE しても監査列が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰がチームテンプレートを作成したか」の操作者証跡温存）。</li>
 * </ol>
 *
 * <p>シード上の注意: team_templates は {@code slug NOT NULL} かつ {@code uq_team_templates_slug UNIQUE} のため、
 * V2.025 のシード値（sports/clinic/... 等）と衝突しない一意な slug（{@code test-tmpl-3d}）を入れる。</p>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.team.migration.FlywayExistingDataTeamTemplatesSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ team_templates 監査列 SET NULL FK撤廃（V105.001）番人テスト")
class FlywayExistingDataTeamTemplatesSetNullFkMigrationTest {

    /** V105.001 の直前バージョン（origin/main 全体最大＝第三陣C）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V105_001_TARGET = "104.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_team_templates_setnull_fk")
            .withUsername("test")
            .withPassword("test")
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
    void startContainer() {
        MYSQL.start();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @Test
    @DisplayName("既存子行を持つDBにV105.001適用_team_templates監査列SET_NULL_FK撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV105_001がteam_templates監査列SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        // given: V105.001 の直前（V104.001）まで適用 ＝ 対象FKはまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V105_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V104.001 までの適用が成功すること").isTrue();

        final long templateCreatedBy; // team_templates.created_by（撤廃対象の監査列）
        final long templateId;

        try (Connection c = conn()) {
            // sanity: V104.001 時点で対象FKが実在すること
            assertThat(foreignKeyExists(c, "team_templates", "fk_team_templates_created_by"))
                    .as("V104.001 時点で fk_team_templates_created_by が実在すること").isTrue();

            templateCreatedBy = insertUser(c, "tt-createdby-3d@example.com");
            // slug NOT NULL + UNIQUE(uq_team_templates_slug) → V2.025 シードと衝突しない一意値を入れる
            templateId = insertTeamTemplate(c, "監査FK撤廃テストテンプレート", "test-tmpl-3d", templateCreatedBy);
        }

        // when: 残りのマイグレーション（V105.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V105.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象FKが撤廃された
            assertThat(foreignKeyExists(c, "team_templates", "fk_team_templates_created_by"))
                    .as("V105.001 で fk_team_templates_created_by が撤廃されること").isFalse();

            // 対象外: slug の UNIQUE 制約は撤廃後も残存していること
            assertThat(uniqueConstraintExists(c, "team_templates", "uq_team_templates_slug"))
                    .as("uq_team_templates_slug（slug UNIQUE）は撤廃対象外で残存すること").isTrue();

            // then-2: 既存子行が生存していること
            assertThat(rowExists(c, "team_templates", templateId))
                    .as("FK 撤廃後も team_templates 子行が生存していること").isTrue();

            // then-3（中核）: 監査列でのみ参照される親 users を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, templateCreatedBy);

            assertThat(rowExists(c, "users", templateCreatedBy)).as("親 users（template created_by）が物理削除されたこと").isFalse();

            assertThat(longColumn(c, "team_templates", "created_by", templateId))
                    .as("team_templates.created_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(templateCreatedBy);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '雛形', '太郎', '雛形太郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** team_templates 行を挿入する。slug は NOT NULL + UNIQUE のため一意値を必須で渡す。 */
    private long insertTeamTemplate(Connection c, String name, String slug, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO team_templates
                    (name, slug, description, is_active, created_by, created_at, updated_at)
                VALUES (?, ?, '創設者証跡温存テスト', 1, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, slug);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void deleteUserPhysically(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    private static boolean rowExists(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static long longColumn(Connection c, String table, String column, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long v = rs.getLong(1);
                return rs.wasNull() ? -1L : v;
            }
        }
    }

    private static boolean foreignKeyExists(Connection c, String table, String constraintName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'FOREIGN KEY'
                """)) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static boolean uniqueConstraintExists(Connection c, String table, String constraintName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'UNIQUE'
                """)) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
