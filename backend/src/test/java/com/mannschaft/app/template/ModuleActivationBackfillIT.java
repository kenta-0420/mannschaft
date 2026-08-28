package com.mannschaft.app.template;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * モジュール有効化バックフィル戦役 PR-A（{@code V158.__module_activation_backfill_grandfather.sql}）の
 * 契約 IT（試練 / red 先行）。
 *
 * <h2>なぜ {@code @SpringBootTest} を使わないのか</h2>
 * <p>通常統合テスト環境（{@code application-test.yml}）は {@code ddl-auto=create} +
 * {@code flyway.enabled=false} で動作し、Flyway マイグレーション（本 backfill 含む）は一切実行されない
 * （メモリ {@code feedback_test_profile_ddl_create_skips_flyway_seed} の罠）。そのため
 * {@link ModuleCatalogWave2SeedIT} と同じく、Spring を起動せず Testcontainers の実 MySQL に対して
 * {@link Flyway} を Java API で直接適用し、適用後の行を JDBC で検証する。</p>
 *
 * <h2>検証内容（受け入れ条件）</h2>
 * <ul>
 *   <li><b>AC-4</b>: blog_cms × ORGANIZATION の module_level_availability.is_available が 1 に是正される</li>
 *   <li><b>AC-6</b>: backfill 対象 slug は全て OPTIONAL（DEFAULT には enable 行を作らない前提）</li>
 *   <li><b>AC-1/AC-7(team)</b>: 既存 team へ budget/workflow の enable 行が is_grandfathered=1・is_enabled=1・
 *       enabled_by=NULL で投入される</li>
 *   <li><b>AC-1/AC-7(org)</b>: 既存 org へ 7 slug の enable 行が is_grandfathered=1 で投入される</li>
 *   <li><b>AC-2(冪等)</b>: 同一 backfill SQL を 2 回流しても行数が増えない</li>
 * </ul>
 *
 * <p>Flyway 適用時点では teams/organizations に seed 行が無いため（migration が 0 行を対象に走る）、
 * 本 IT は適用後にテスト内で teams/organizations に 1 行 INSERT し、migration と同一の backfill SQL を
 * 手で流して検証する（これにより NOT EXISTS 冪等性も同時に検証できる）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.template.ModuleActivationBackfillIT#isDockerAvailable")
@DisplayName("モジュール有効化バックフィル 契約IT（試練・V158）")
class ModuleActivationBackfillIT {

    /** team バックフィル対象 slug。 */
    private static final String[] TEAM_SLUGS = {"budget", "workflow"};

    /** organization バックフィル対象 slug。 */
    private static final String[] ORG_SLUGS =
            {"tournament", "timetable", "committee", "budget", "form", "workflow", "blog_cms"};

    /** V158 (3) と完全一致する team バックフィル SQL（冪等性検証のため同一文を再利用する）。 */
    private static final String TEAM_BACKFILL_SQL =
            "INSERT INTO team_enabled_modules "
                    + "(team_id, module_id, is_enabled, is_grandfathered, enabled_at, enabled_by, "
                    + "trial_used, created_at, updated_at) "
                    + "SELECT t.id, m.id, 1, 1, NOW(), NULL, 0, NOW(), NOW() "
                    + "FROM teams t "
                    + "JOIN module_definitions m ON m.slug IN ('budget','workflow') AND m.deleted_at IS NULL "
                    + "WHERE t.deleted_at IS NULL "
                    + "  AND NOT EXISTS (SELECT 1 FROM team_enabled_modules e "
                    + "                  WHERE e.team_id = t.id AND e.module_id = m.id)";

    /** V158 (4) と完全一致する organization バックフィル SQL。 */
    private static final String ORG_BACKFILL_SQL =
            "INSERT INTO organization_enabled_modules "
                    + "(organization_id, module_id, is_enabled, is_grandfathered, enabled_at, enabled_by, "
                    + "created_at, updated_at) "
                    + "SELECT o.id, m.id, 1, 1, NOW(), NULL, NOW(), NOW() "
                    + "FROM organizations o "
                    + "JOIN module_definitions m "
                    + "  ON m.slug IN ('tournament','timetable','committee','budget','form','workflow','blog_cms') "
                    + " AND m.deleted_at IS NULL "
                    + "WHERE o.deleted_at IS NULL "
                    + "  AND NOT EXISTS (SELECT 1 FROM organization_enabled_modules e "
                    + "                  WHERE e.organization_id = o.id AND e.module_id = m.id)";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_backfill")
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
    void migrate() {
        MYSQL.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @Nested
    @DisplayName("AC-4: blog_cms 組織レベル availability 是正")
    class BlogCmsOrgAvailability {

        @Test
        @DisplayName("blog_cms × ORGANIZATION の is_available が 1 に是正される")
        void blogCmsOrgAvailabilityが1() throws Exception {
            Integer available = null;
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT mla.is_available FROM module_level_availability mla "
                                 + "JOIN module_definitions md ON md.id = mla.module_id "
                                 + "WHERE md.slug = 'blog_cms' AND mla.level = 'ORGANIZATION'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        available = rs.getInt("is_available");
                    }
                }
            }
            assertThat(available)
                    .as("blog_cms × ORGANIZATION の availability 行が存在すること")
                    .isNotNull();
            assertThat(available)
                    .as("Wave2 で 0 と誤登録された blog_cms/ORGANIZATION が V158 で 1 に是正されること")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AC-6: バックフィル対象 slug は全て OPTIONAL")
    class TargetSlugsAreOptional {

        @Test
        @DisplayName("team/org バックフィル対象 slug は module_type=OPTIONAL")
        void 対象slugは全てOPTIONAL() throws Exception {
            java.util.Set<String> allSlugs = new java.util.LinkedHashSet<>();
            java.util.Collections.addAll(allSlugs, TEAM_SLUGS);
            java.util.Collections.addAll(allSlugs, ORG_SLUGS);

            for (String slug : allSlugs) {
                String type = null;
                try (Connection c = conn();
                     PreparedStatement ps = c.prepareStatement(
                             "SELECT module_type FROM module_definitions WHERE slug = ?")) {
                    ps.setString(1, slug);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            type = rs.getString("module_type");
                        }
                    }
                }
                assertThat(type).as("slug=%s が module_definitions に存在すること", slug).isNotNull();
                assertThat(type).as("slug=%s は OPTIONAL（DEFAULT に enable 行を作らない前提）", slug)
                        .isEqualTo("OPTIONAL");
            }
        }
    }

    @Nested
    @DisplayName("AC-1/AC-7/AC-2: 既存テナントへのグランドファザリング投入と冪等性")
    class GrandfatherBackfill {

        @Test
        @DisplayName("team: budget/workflow の enable 行が is_grandfathered=1・is_enabled=1・enabled_by=NULL で投入され、再実行しても増えない")
        void teamバックフィルとチーム冪等性() throws Exception {
            try (Connection c = conn()) {
                long teamId = insertTeam(c, "IT バックフィル team");

                // 1回目の backfill
                execUpdate(c, TEAM_BACKFILL_SQL);
                long afterFirst = countTeamGrandfathered(c, teamId);
                assertThat(afterFirst)
                        .as("team に budget/workflow の grandfather 行が 2 本投入される")
                        .isEqualTo(TEAM_SLUGS.length);

                // 各行の属性検証
                assertThat(countTeamRows(c, teamId,
                        "is_enabled = 1 AND is_grandfathered = 1 AND enabled_by IS NULL"))
                        .as("投入行は is_enabled=1・is_grandfathered=1・enabled_by=NULL")
                        .isEqualTo(TEAM_SLUGS.length);

                // 対象 slug が budget/workflow に一致すること
                assertThat(enabledSlugs(c,
                        "SELECT md.slug FROM team_enabled_modules e "
                                + "JOIN module_definitions md ON md.id = e.module_id "
                                + "WHERE e.team_id = " + teamId + " AND e.is_grandfathered = 1"))
                        .containsExactlyInAnyOrder(TEAM_SLUGS);

                // 2回目の backfill（冪等性 AC-2）
                execUpdate(c, TEAM_BACKFILL_SQL);
                long afterSecond = countTeamGrandfathered(c, teamId);
                assertThat(afterSecond)
                        .as("同一 backfill SQL を再実行しても行数は増えない（NOT EXISTS 冪等）")
                        .isEqualTo(afterFirst);
            }
        }

        @Test
        @DisplayName("org: 7 slug の enable 行が is_grandfathered=1 で投入され、再実行しても増えない")
        void orgバックフィルと組織冪等性() throws Exception {
            try (Connection c = conn()) {
                long orgId = insertOrganization(c, "IT バックフィル org");

                execUpdate(c, ORG_BACKFILL_SQL);
                long afterFirst = countOrgGrandfathered(c, orgId);
                assertThat(afterFirst)
                        .as("org に 7 slug の grandfather 行が投入される")
                        .isEqualTo(ORG_SLUGS.length);

                assertThat(countOrgRows(c, orgId,
                        "is_enabled = 1 AND is_grandfathered = 1 AND enabled_by IS NULL"))
                        .as("投入行は is_enabled=1・is_grandfathered=1・enabled_by=NULL")
                        .isEqualTo(ORG_SLUGS.length);

                assertThat(enabledSlugs(c,
                        "SELECT md.slug FROM organization_enabled_modules e "
                                + "JOIN module_definitions md ON md.id = e.module_id "
                                + "WHERE e.organization_id = " + orgId + " AND e.is_grandfathered = 1"))
                        .containsExactlyInAnyOrder(ORG_SLUGS);

                execUpdate(c, ORG_BACKFILL_SQL);
                long afterSecond = countOrgGrandfathered(c, orgId);
                assertThat(afterSecond)
                        .as("同一 backfill SQL を再実行しても行数は増えない（NOT EXISTS 冪等）")
                        .isEqualTo(afterFirst);
            }
        }
    }

    // ========================================
    // JDBC ヘルパー
    // ========================================

    /**
     * teams に 1 行 INSERT する。実 DDL の NOT NULL・DEFAULT 無し列を全網羅する
     * （V71 で追加された slug NOT NULL UNIQUE を含む）。列リストは既存 IT
     * {@code BulletinThreadVisibilityResolverIntegrationTest#insertTeam} を金型に流用。
     * slug は UNIQUE のため UUID 由来の一意値を付与する。deleted_at は NULL
     * （backfill が deleted_at IS NULL を対象とするため NULL であることが必須）。
     */
    private long insertTeam(Connection c, String name) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), "
                        + "NOW(), NOW())",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * organizations に 1 行 INSERT する。実 DDL の NOT NULL・DEFAULT 無し列を全網羅する
     * （V71 で追加された slug NOT NULL UNIQUE を含む）。列リストは既存 IT
     * {@code BulletinThreadVisibilityResolverIntegrationTest#insertOrganization} を金型に流用。
     */
    private long insertOrganization(Connection c, String name) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (?, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void execUpdate(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private long countTeamGrandfathered(Connection c, long teamId) throws Exception {
        return countTeamRows(c, teamId, "is_grandfathered = 1");
    }

    private long countTeamRows(Connection c, long teamId, String extraWhere) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM team_enabled_modules WHERE team_id = ? AND " + extraWhere)) {
            ps.setLong(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countOrgGrandfathered(Connection c, long orgId) throws Exception {
        return countOrgRows(c, orgId, "is_grandfathered = 1");
    }

    private long countOrgRows(Connection c, long orgId, String extraWhere) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM organization_enabled_modules WHERE organization_id = ? AND "
                        + extraWhere)) {
            ps.setLong(1, orgId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String[] enabledSlugs(Connection c, String sql) throws Exception {
        java.util.List<String> slugs = new java.util.ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                slugs.add(rs.getString(1));
            }
        }
        return slugs.toArray(new String[0]);
    }
}
