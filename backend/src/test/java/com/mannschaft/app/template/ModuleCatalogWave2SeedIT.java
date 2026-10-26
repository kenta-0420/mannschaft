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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * モジュールカタログ登録 Wave2（{@code V156.__seed_module_definitions_wave2.sql}）の
 * seed 内容を検証する契約 IT（試練 / red 先行）。
 *
 * <h2>なぜ {@code AbstractMySqlIntegrationTest}（@SpringBootTest）を使わないのか</h2>
 * <p>本プロジェクトの通常統合テスト環境（{@code src/test/resources/application-test.yml}）は
 * {@code spring.jpa.hibernate.ddl-auto=create} + {@code spring.flyway.enabled=false} で動作する。
 * すなわちテスト DB スキーマは Entity から生成され、<b>Flyway マイグレーション（seed 含む）は
 * 一切実行されない</b>。したがって注入 Repository で seed 行を検証しようとしても行は存在せず、
 * 常に空＝偽赤になる（メモリ {@code feedback_test_profile_ddl_create_skips_flyway_seed} の罠）。</p>
 *
 * <p>seed を実際に適用して検証するには、{@code FlywayFromScratchMigrationTest} と同じく
 * Spring コンテキストを起動せず Testcontainers の実 MySQL に対して {@link Flyway} を
 * Java API で直接実行し、適用後の行を JDBC で検証するのが唯一の正しい方法である。
 * 本テストはその金型（{@code ModuleCatalogWave1SeedIT}）に倣う。</p>
 *
 * <h2>red 先行性</h2>
 * <p>V156 を適用しなければ wave2 の 13 slug は 1 行も存在せず、下記アサーションはすべて赤になる。
 * V156 適用後に緑化する（試練→出陣）。</p>
 *
 * <h2>検証内容（軍議 受け入れ条件）</h2>
 * <ol>
 *   <li>13 slug が module_definitions に module_type='OPTIONAL' / is_active=1 で存在</li>
 *   <li>各 slug の module_number（56/58-67/69/70）・requires_paid_plan・trial_days
 *       （succession のみ 1/30、他は 0/14）</li>
 *   <li>各 slug×3レベルの module_level_availability 行（計 39）が期待マトリクスどおり</li>
 *   <li>template_modules が計 15 本、各 template が期待 team_templates.slug に解決</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.template.ModuleCatalogWave2SeedIT#isDockerAvailable")
@DisplayName("モジュールカタログ Wave2 seed 契約IT（試練・V156）")
class ModuleCatalogWave2SeedIT {

    /** Wave2 の 13 slug（登録順）。 */
    private static final List<String> WAVE2_SLUGS = List.of(
            "job_matching", "promotion", "point_card", "blog_cms", "memo",
            "contact", "social", "reflection", "family", "succession",
            "market", "workflow", "ticket");

    /** slug -> 期待 module_number。 */
    private static final Map<String, Integer> EXPECTED_MODULE_NUMBER = Map.ofEntries(
            Map.entry("job_matching", 56),
            Map.entry("promotion", 58),
            Map.entry("point_card", 59),
            Map.entry("blog_cms", 60),
            Map.entry("memo", 61),
            Map.entry("contact", 62),
            Map.entry("social", 63),
            Map.entry("reflection", 64),
            Map.entry("family", 65),
            Map.entry("succession", 66),
            Map.entry("market", 67),
            Map.entry("workflow", 69),
            Map.entry("ticket", 70));

    /** slug -> 期待 requires_paid_plan（succession のみ 1、他は 0）。 */
    private static final Map<String, Integer> EXPECTED_REQUIRES_PAID_PLAN = buildRequiresPaidPlanMap();

    /** slug -> 期待 trial_days（succession のみ 30、他は 14）。 */
    private static final Map<String, Integer> EXPECTED_TRIAL_DAYS = buildTrialDaysMap();

    /** (slug, level) -> 期待 is_available（1/0）。計 39 エントリ。 */
    private static final Map<String, Integer> EXPECTED_AVAILABILITY = buildAvailabilityMatrix();

    /** 期待 (module_slug, template_slug) ペア。計 15。 */
    private static final Set<String> EXPECTED_TEMPLATE_PAIRS = Set.of(
            "job_matching|restaurant",
            "job_matching|gym",
            "promotion|restaurant",
            "promotion|salon",
            "promotion|gym",
            "point_card|restaurant",
            "point_card|salon",
            "point_card|gym",
            "blog_cms|community",
            "reflection|school",
            "succession|apartment",
            "workflow|company",
            "workflow|apartment",
            "ticket|gym",
            "ticket|sports");

    private static Map<String, Integer> buildRequiresPaidPlanMap() {
        Map<String, Integer> m = new HashMap<>();
        for (String slug : WAVE2_SLUGS) {
            m.put(slug, "succession".equals(slug) ? 1 : 0);
        }
        return m;
    }

    private static Map<String, Integer> buildTrialDaysMap() {
        Map<String, Integer> m = new HashMap<>();
        for (String slug : WAVE2_SLUGS) {
            m.put(slug, "succession".equals(slug) ? 30 : 14);
        }
        return m;
    }

    private static Map<String, Integer> buildAvailabilityMatrix() {
        Map<String, Integer> m = new HashMap<>();
        // ORG, TEAM, PERSONAL の順
        putLevel(m, "job_matching", 0, 1, 1);
        putLevel(m, "promotion", 1, 1, 0);
        putLevel(m, "point_card", 1, 0, 1);
        // V158（有効化バックフィル）で blog_cms 組織レベルを 0→1 是正したため、Wave2 IT の期待も追随。
        // Testcontainers は Flyway を V158 まで全適用するため、blog_cms × ORGANIZATION は 1 になる。
        putLevel(m, "blog_cms", 1, 1, 1);
        putLevel(m, "memo", 0, 0, 1);
        putLevel(m, "contact", 0, 0, 1);
        putLevel(m, "social", 0, 0, 1);
        putLevel(m, "reflection", 0, 0, 1);
        putLevel(m, "family", 0, 1, 0);
        putLevel(m, "succession", 1, 0, 0);
        putLevel(m, "market", 1, 1, 0);
        putLevel(m, "workflow", 1, 1, 0);
        putLevel(m, "ticket", 0, 1, 1);
        return m;
    }

    private static void putLevel(Map<String, Integer> m, String slug, int org, int team, int personal) {
        m.put(slug + "|ORGANIZATION", org);
        m.put(slug + "|TEAM", team);
        m.put(slug + "|PERSONAL", personal);
    }

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_wave2seed")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            // FlywayFromScratchMigrationTest と同条件（CREATE TRIGGER を含むマイグレーションのため）。
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
    @DisplayName("1. module_definitions（定義本体）")
    class ModuleDefinitions {

        @Test
        @DisplayName("13 slug が OPTIONAL / is_active=1 で存在し、module_number・requires_paid_plan・trial_days が期待どおり")
        void wave2_13モジュールが期待属性で登録されている() throws Exception {
            Map<String, Object[]> actual = new HashMap<>();
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT slug, module_type, is_active, module_number, requires_paid_plan, trial_days "
                                 + "FROM module_definitions WHERE slug IN "
                                 + inClause(WAVE2_SLUGS.size()))) {
                bindSlugs(ps, WAVE2_SLUGS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        actual.put(rs.getString("slug"), new Object[]{
                                rs.getString("module_type"),
                                rs.getInt("is_active"),
                                rs.getInt("module_number"),
                                rs.getInt("requires_paid_plan"),
                                rs.getInt("trial_days")
                        });
                    }
                }
            }

            assertThat(actual.keySet())
                    .as("Wave2 の 13 slug がすべて module_definitions に存在すること")
                    .containsExactlyInAnyOrderElementsOf(WAVE2_SLUGS);

            for (String slug : WAVE2_SLUGS) {
                Object[] row = actual.get(slug);
                assertThat(row).as("slug=%s の行が存在すること", slug).isNotNull();
                assertThat(row[0]).as("slug=%s は OPTIONAL", slug).isEqualTo("OPTIONAL");
                assertThat(row[1]).as("slug=%s は is_active=1", slug).isEqualTo(1);
                assertThat(row[2]).as("slug=%s の module_number", slug)
                        .isEqualTo(EXPECTED_MODULE_NUMBER.get(slug));
                assertThat(row[3]).as("slug=%s の requires_paid_plan", slug)
                        .isEqualTo(EXPECTED_REQUIRES_PAID_PLAN.get(slug));
                assertThat(row[4]).as("slug=%s の trial_days", slug)
                        .isEqualTo(EXPECTED_TRIAL_DAYS.get(slug));
            }
        }
    }

    @Nested
    @DisplayName("2. module_level_availability（レベル別可否・39行）")
    class LevelAvailability {

        @Test
        @DisplayName("各 slug×3レベル 計39行が期待マトリクスどおり")
        void レベル別可否39行が期待どおり() throws Exception {
            Map<String, Integer> actual = new HashMap<>();
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT md.slug AS slug, mla.level AS level, mla.is_available AS is_available "
                                 + "FROM module_level_availability mla "
                                 + "JOIN module_definitions md ON md.id = mla.module_id "
                                 + "WHERE md.slug IN " + inClause(WAVE2_SLUGS.size()))) {
                bindSlugs(ps, WAVE2_SLUGS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        actual.put(rs.getString("slug") + "|" + rs.getString("level"),
                                rs.getInt("is_available"));
                    }
                }
            }

            assertThat(actual)
                    .as("Wave2 13モジュール × 3レベル = 39 行が存在すること")
                    .hasSize(39);
            assertThat(actual)
                    .as("(slug, level) -> is_available が期待マトリクスに完全一致すること")
                    .containsExactlyInAnyOrderEntriesOf(EXPECTED_AVAILABILITY);
        }
    }

    @Nested
    @DisplayName("3. template_modules（テンプレ紐付け・15本）")
    class TemplateModules {

        @Test
        @DisplayName("15本が期待 team_templates.slug に解決する")
        void テンプレ紐付け15本が期待どおり() throws Exception {
            Set<String> actual = new LinkedHashSet<>();
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT md.slug AS module_slug, tt.slug AS template_slug "
                                 + "FROM template_modules tm "
                                 + "JOIN module_definitions md ON md.id = tm.module_id "
                                 + "JOIN team_templates tt ON tt.id = tm.template_id "
                                 + "WHERE md.slug IN " + inClause(WAVE2_SLUGS.size()))) {
                bindSlugs(ps, WAVE2_SLUGS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        actual.add(rs.getString("module_slug") + "|" + rs.getString("template_slug"));
                    }
                }
            }

            assertThat(actual)
                    .as("Wave2 の template_modules は計 15 本")
                    .hasSize(15);
            assertThat(actual)
                    .as("(module_slug, template_slug) ペアが期待集合に完全一致すること")
                    .isEqualTo(new HashSet<>(EXPECTED_TEMPLATE_PAIRS));
        }
    }

    private static String inClause(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.append(")").toString();
    }

    private static void bindSlugs(PreparedStatement ps, List<String> slugs) throws Exception {
        for (int i = 0; i < slugs.size(); i++) {
            ps.setString(i + 1, slugs.get(i));
        }
    }
}
