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
 * モジュールカタログ登録 Wave1（{@code V155.__seed_module_definitions_wave1.sql}）の
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
 * 本テストはその金型に倣う。</p>
 *
 * <h2>red 先行性</h2>
 * <p>V155 を適用しなければ wave1 の 8 slug は 1 行も存在せず、下記アサーションはすべて赤になる。
 * V155 適用後に緑化する（試練→出陣）。</p>
 *
 * <h2>検証内容（軍議 受け入れ条件）</h2>
 * <ol>
 *   <li>8 slug が module_definitions に module_type='OPTIONAL' / is_active=1 で存在</li>
 *   <li>各 slug の module_number（50/51/52/53/54/55/57/68）・requires_paid_plan=0・trial_days=14</li>
 *   <li>各 slug×3レベルの module_level_availability 行（計 24）が期待マトリクスどおり</li>
 *   <li>template_modules が計 13 本、各 template が期待 team_templates.slug に解決</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.template.ModuleCatalogWave1SeedIT#isDockerAvailable")
@DisplayName("モジュールカタログ Wave1 seed 契約IT（試練・V155）")
class ModuleCatalogWave1SeedIT {

    /** Wave1 の 8 slug（登録順）。 */
    private static final List<String> WAVE1_SLUGS = List.of(
            "tournament", "event", "budget", "school_attendance",
            "timetable", "recruitment", "committee", "form");

    /** slug -> 期待 module_number。 */
    private static final Map<String, Integer> EXPECTED_MODULE_NUMBER = Map.of(
            "tournament", 50,
            "event", 51,
            "budget", 52,
            "school_attendance", 53,
            "timetable", 54,
            "recruitment", 55,
            "committee", 57,
            "form", 68);

    /** (slug, level) -> 期待 is_available（1/0）。計 24 エントリ。 */
    private static final Map<String, Integer> EXPECTED_AVAILABILITY = buildAvailabilityMatrix();

    /** 期待 (module_slug, template_slug) ペア。計 13。 */
    private static final Set<String> EXPECTED_TEMPLATE_PAIRS = Set.of(
            "tournament|sports",
            "event|community",
            "event|sports",
            "budget|company",
            "budget|sports",
            "school_attendance|school",
            "timetable|school",
            "recruitment|sports",
            "recruitment|community",
            "committee|apartment",
            "committee|neighborhood",
            "committee|community",
            "form|company");

    private static Map<String, Integer> buildAvailabilityMatrix() {
        Map<String, Integer> m = new HashMap<>();
        // ORG, TEAM, PERSONAL の順
        putLevel(m, "tournament", 1, 1, 0);
        putLevel(m, "event", 1, 1, 0);
        putLevel(m, "budget", 1, 1, 0);
        putLevel(m, "school_attendance", 0, 1, 1);
        putLevel(m, "timetable", 1, 1, 1);
        putLevel(m, "recruitment", 1, 1, 1);
        putLevel(m, "committee", 1, 0, 0);
        putLevel(m, "form", 1, 1, 0);
        return m;
    }

    private static void putLevel(Map<String, Integer> m, String slug, int org, int team, int personal) {
        m.put(slug + "|ORGANIZATION", org);
        m.put(slug + "|TEAM", team);
        m.put(slug + "|PERSONAL", personal);
    }

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_wave1seed")
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
        @DisplayName("8 slug が OPTIONAL / is_active=1 で存在し、module_number・requires_paid_plan・trial_days が期待どおり")
        void wave1_8モジュールが期待属性で登録されている() throws Exception {
            Map<String, Object[]> actual = new HashMap<>();
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT slug, module_type, is_active, module_number, requires_paid_plan, trial_days "
                                 + "FROM module_definitions WHERE slug IN "
                                 + inClause(WAVE1_SLUGS.size()))) {
                bindSlugs(ps, WAVE1_SLUGS);
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
                    .as("Wave1 の 8 slug がすべて module_definitions に存在すること")
                    .containsExactlyInAnyOrderElementsOf(WAVE1_SLUGS);

            for (String slug : WAVE1_SLUGS) {
                Object[] row = actual.get(slug);
                assertThat(row).as("slug=%s の行が存在すること", slug).isNotNull();
                assertThat(row[0]).as("slug=%s は OPTIONAL", slug).isEqualTo("OPTIONAL");
                assertThat(row[1]).as("slug=%s は is_active=1", slug).isEqualTo(1);
                assertThat(row[2]).as("slug=%s の module_number", slug)
                        .isEqualTo(EXPECTED_MODULE_NUMBER.get(slug));
                assertThat(row[3]).as("slug=%s は requires_paid_plan=0", slug).isEqualTo(0);
                assertThat(row[4]).as("slug=%s は trial_days=14", slug).isEqualTo(14);
            }
        }
    }

    @Nested
    @DisplayName("2. module_level_availability（レベル別可否・24行）")
    class LevelAvailability {

        @Test
        @DisplayName("各 slug×3レベル 計24行が期待マトリクスどおり")
        void レベル別可否24行が期待どおり() throws Exception {
            Map<String, Integer> actual = new HashMap<>();
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT md.slug AS slug, mla.level AS level, mla.is_available AS is_available "
                                 + "FROM module_level_availability mla "
                                 + "JOIN module_definitions md ON md.id = mla.module_id "
                                 + "WHERE md.slug IN " + inClause(WAVE1_SLUGS.size()))) {
                bindSlugs(ps, WAVE1_SLUGS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        actual.put(rs.getString("slug") + "|" + rs.getString("level"),
                                rs.getInt("is_available"));
                    }
                }
            }

            assertThat(actual)
                    .as("Wave1 8モジュール × 3レベル = 24 行が存在すること")
                    .hasSize(24);
            assertThat(actual)
                    .as("(slug, level) -> is_available が期待マトリクスに完全一致すること")
                    .containsExactlyInAnyOrderEntriesOf(EXPECTED_AVAILABILITY);
        }
    }

    @Nested
    @DisplayName("3. template_modules（テンプレ紐付け・13本）")
    class TemplateModules {

        @Test
        @DisplayName("13本が期待 team_templates.slug に解決する")
        void テンプレ紐付け13本が期待どおり() throws Exception {
            Set<String> actual = new LinkedHashSet<>();
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT md.slug AS module_slug, tt.slug AS template_slug "
                                 + "FROM template_modules tm "
                                 + "JOIN module_definitions md ON md.id = tm.module_id "
                                 + "JOIN team_templates tt ON tt.id = tm.template_id "
                                 + "WHERE md.slug IN " + inClause(WAVE1_SLUGS.size()))) {
                bindSlugs(ps, WAVE1_SLUGS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        actual.add(rs.getString("module_slug") + "|" + rs.getString("template_slug"));
                    }
                }
            }

            assertThat(actual)
                    .as("Wave1 の template_modules は計 13 本")
                    .hasSize(13);
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
