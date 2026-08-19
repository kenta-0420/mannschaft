package com.mannschaft.app.survey.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-041 試練: {@code MANAGE_SURVEYS} 権限カタログ登録 migration
 * （{@code V187.20260819090014__add_manage_surveys_to_catalog.sql}）の受け入れテスト。
 *
 * <h2>なぜ Flyway を直接叩くのか</h2>
 * <p>test profile（{@code src/test/resources/application-test.yml}）は
 * {@code spring.flyway.enabled: false} かつ {@code ddl-auto: create} であり、
 * <b>スキーマは Entity 由来・Flyway のシードは一切入らない</b>。したがって
 * {@code @SpringBootTest} 系の統合テストでは {@code permissions} / {@code role_permissions} は
 * 空表であり、「migration が何を入れたか」を検証することは原理的にできない。
 * 本テストは {@code FlywayExistingData*MigrationTest} 群と同じく Spring を起動せず、
 * Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接適用して検証する。</p>
 *
 * <h2>受け入れ条件</h2>
 * <ul>
 *   <li>AC-1: 実 DB の {@code permissions} に {@code MANAGE_SURVEYS} が 1 行存在する</li>
 *   <li>AC-2: migration 本文を 2 度目に適用しても行が増えない（冪等）</li>
 *   <li>AC-3: {@code role_permissions} に ADMIN×{@code MANAGE_SURVEYS} が {@code is_default=1} で 1 行、
 *       DEPUTY_ADMIN の行は 0 行</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.survey.migration.ManageSurveysPermissionCatalogMigrationTest#isDockerAvailable")
@DisplayName("CMP-041: MANAGE_SURVEYS 権限カタログ登録 migration 番人テスト")
class ManageSurveysPermissionCatalogMigrationTest {

    /** 検証対象 migration のリソースパス（本文の再適用＝冪等検証にも用いる）。 */
    private static final String MIGRATION_RESOURCE =
            "db/migration/V187.20260819090014__add_manage_surveys_to_catalog.sql";

    private static final String PERMISSION_NAME = "MANAGE_SURVEYS";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_manage_surveys")
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
    void startContainerAndMigrate() {
        MYSQL.start();
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult result = flyway.migrate();
        assertThat(result.success)
                .as("全 migration（V187 の MANAGE_SURVEYS 登録を含む）が成功すること")
                .isTrue();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    // =====================================================================
    // AC-1
    // =====================================================================

    @Test
    @DisplayName("AC-1: permissions に MANAGE_SURVEYS が 1 行存在する")
    void ac1_permissionsにMANAGE_SURVEYSが1行存在する() throws Exception {
        try (Connection c = conn()) {
            assertThat(countPermission(c))
                    .as("権限名の正本は Flyway の INSERT INTO permissions のみであり、"
                            + "カタログに無い名前で認可を書いても静かに不成立になる")
                    .isEqualTo(1);
        }
    }

    // =====================================================================
    // AC-2
    // =====================================================================

    /**
     * AC-2: migration 本文を 2 度目に適用しても行が増えないこと（冪等）。
     *
     * <p>Flyway は同じバージョンを二度走らせないため、Flyway 経由の再実行では冪等性を
     * 測れない（測れていない機構は守っていない）。ここでは migration ファイルの本文を
     * classpath から読み出して直接 2 度目を適用し、{@code NOT EXISTS} ガードが実際に
     * 効いていることを実証する。</p>
     */
    @Test
    @DisplayName("AC-2: migration 本文を再適用しても permissions / role_permissions の行が増えない")
    void ac2_migration再適用で行が増えない() throws Exception {
        try (Connection c = conn()) {
            int permissionsBefore = countPermission(c);
            int adminRowsBefore = countRolePermission(c, "ADMIN");
            int allRolePermissionsBefore = countAllRolePermissionsForPermission(c);

            applyMigrationBodyAgain(c);

            assertThat(countPermission(c))
                    .as("permissions への 2 度目の適用で行が増えてはならない")
                    .isEqualTo(permissionsBefore);
            assertThat(countRolePermission(c, "ADMIN"))
                    .as("role_permissions の ADMIN 行が 2 度目の適用で増えてはならない")
                    .isEqualTo(adminRowsBefore);
            assertThat(countAllRolePermissionsForPermission(c))
                    .as("MANAGE_SURVEYS に紐づく role_permissions の総行数が変わってはならない")
                    .isEqualTo(allRolePermissionsBefore);
        }
    }

    // =====================================================================
    // AC-3
    // =====================================================================

    @Test
    @DisplayName("AC-3: role_permissions は ADMIN×is_default=1 が1行のみ・DEPUTY_ADMIN は0行")
    void ac3_ADMINのみis_default1でDEPUTY_ADMINは0行() throws Exception {
        try (Connection c = conn()) {
            assertThat(countRolePermissionWithDefault(c, "ADMIN", 1))
                    .as("F03.11 §13「ADMIN: 自動付与」— ADMIN には is_default=1 の行が 1 行だけ必要")
                    .isEqualTo(1);
            assertThat(countRolePermission(c, "ADMIN"))
                    .as("ADMIN の行は is_default=1 の 1 行のみであるべき")
                    .isEqualTo(1);
            assertThat(countRolePermission(c, "DEPUTY_ADMIN"))
                    .as("DEPUTY_ADMIN へ天井行（is_default=0）を置くと、"
                            + "RoleService.resolveEffectivePermissions が is_default を見ずに集約するため"
                            + "権限を個別付与していない副管理者全員へ黙って権限が渡る")
                    .isZero();
            assertThat(countAllRolePermissionsForPermission(c))
                    .as("MANAGE_SURVEYS を持つ role_permissions 行は ADMIN の 1 行のみであるべき")
                    .isEqualTo(1);
        }
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private int countPermission(Connection c) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM permissions WHERE name = ?", PERMISSION_NAME);
    }

    private int countRolePermission(Connection c, String roleName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM role_permissions rp "
                        + "JOIN roles r ON r.id = rp.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE r.name = ? AND p.name = ?")) {
            ps.setString(1, roleName);
            ps.setString(2, PERMISSION_NAME);
            return single(ps);
        }
    }

    private int countRolePermissionWithDefault(Connection c, String roleName, int isDefault)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM role_permissions rp "
                        + "JOIN roles r ON r.id = rp.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE r.name = ? AND p.name = ? AND rp.is_default = ?")) {
            ps.setString(1, roleName);
            ps.setString(2, PERMISSION_NAME);
            ps.setInt(3, isDefault);
            return single(ps);
        }
    }

    private int countAllRolePermissionsForPermission(Connection c) throws SQLException {
        return scalar(c,
                "SELECT COUNT(*) FROM role_permissions rp "
                        + "JOIN permissions p ON p.id = rp.permission_id WHERE p.name = ?",
                PERMISSION_NAME);
    }

    private int scalar(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            return single(ps);
        }
    }

    private int single(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** migration ファイル本文を読み出し、そのまま 2 度目に適用する。 */
    private void applyMigrationBodyAgain(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            for (String sql : readMigrationStatements()) {
                st.execute(sql);
            }
        }
    }

    /** migration ファイルをコメント除去のうえ {@code ;} 区切りの文へ分解する。 */
    private List<String> readMigrationStatements() throws IOException {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in).as("migration ファイルが classpath 上に存在すること: " + MIGRATION_RESOURCE).isNotNull();
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder stripped = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            stripped.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : stripped.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        assertThat(statements)
                .as("migration には少なくとも permissions / role_permissions の 2 文が含まれるはず")
                .hasSizeGreaterThanOrEqualTo(2);
        return statements;
    }
}
