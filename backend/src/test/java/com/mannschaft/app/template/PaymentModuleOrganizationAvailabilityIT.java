package com.mannschaft.app.template;

import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.entity.ModuleLevelAvailabilityEntity;
import com.mannschaft.app.template.entity.OrganizationEnabledModuleEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.ModuleLevelAvailabilityRepository;
import com.mannschaft.app.template.repository.ModuleRecommendationRepository;
import com.mannschaft.app.template.repository.OrganizationEnabledModuleRepository;
import com.mannschaft.app.template.repository.TeamEnabledModuleRepository;
import com.mannschaft.app.template.repository.TeamTemplateRepository;
import com.mannschaft.app.template.repository.TemplateModuleRepository;
import com.mannschaft.app.template.service.ModuleService;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * payment（決済）モジュールが ORGANIZATION レベルで有効化できることを検証する契約 IT。
 *
 * <p>背景: {@code V2.027} のシードは「基本 ORGANIZATION=false」という機械的既定のまま payment を
 * ORGANIZATION=0 で残していた。しかし組織の支払い機能は全層で実装済みである
 * （{@code payment_items.organization_id} と {@code chk_pi_scope} 制約、
 * {@code OrganizationPaymentController} / {@code OrganizationPaymentItemController}、
 * FE の {@code organizations/[slug]/payments.vue}、設計書 F08.2 の対象レベル「組織 (Organization)」）。
 * ORGANIZATION=0 のままだと {@code ModuleService.toggleOrganizationModule} がレベルチェックで
 * TMPL_005 を投げ、組織 ADMIN が payment を有効化できず、組織サイドバーの領収書導線が永久に出ない。
 * V208 のマイグレーションでこれを 1 に是正する。</p>
 *
 * <h2>なぜ {@code @SpringBootTest} を使わないのか</h2>
 * <p>通常の統合テスト環境（{@code src/test/resources/application-test.yml}）は
 * {@code ddl-auto=create} + {@code flyway.enabled=false} であり、<b>Flyway のシードが一切走らない</b>。
 * シード行を Repository で検証しても常に空＝偽赤になる。{@code ModuleCatalogWave1SeedIT} と同じく
 * Testcontainers の実 MySQL に Flyway を Java API で直接適用し、JDBC で検証する金型に倣う。</p>
 *
 * <h2>検証内容</h2>
 * <ol>
 *   <li>payment の {@code module_level_availability} が ORGANIZATION=1 / TEAM=1 / PERSONAL=0</li>
 *   <li>payment は {@code requires_paid_plan=0}（＝無料解放。有料ゲート TMPL_004 に掛からない）</li>
 *   <li>実 DB から読んだレベル可否を用いて {@code toggleOrganizationModule} が TMPL_005 を投げない</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.template.PaymentModuleOrganizationAvailabilityIT#isDockerAvailable")
@DisplayName("payment モジュールの組織レベル有効化 契約IT（V208）")
class PaymentModuleOrganizationAvailabilityIT {

    private static final String PAYMENT_SLUG = "payment";
    private static final Long ORG_ID = 200L;
    private static final Long USER_ID = 100L;

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_payment_org_avail")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            // CREATE TRIGGER を含むマイグレーションがあるため FlywayFromScratchMigrationTest と同条件。
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

    /** payment のレベル別可否を level -&gt; is_available で読み出す。 */
    private Map<String, Integer> readPaymentAvailability() throws Exception {
        Map<String, Integer> actual = new HashMap<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT mla.level AS level, mla.is_available AS is_available "
                             + "FROM module_level_availability mla "
                             + "JOIN module_definitions md ON md.id = mla.module_id "
                             + "WHERE md.slug = ?")) {
            ps.setString(1, PAYMENT_SLUG);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    actual.put(rs.getString("level"), rs.getInt("is_available"));
                }
            }
        }
        return actual;
    }

    /** payment の (module_id, requires_paid_plan) を読み出す。 */
    private long readPaymentModuleId() throws Exception {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM module_definitions WHERE slug = ?")) {
            ps.setString(1, PAYMENT_SLUG);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("payment の定義行が存在すること").isTrue();
                return rs.getLong("id");
            }
        }
    }

    @Nested
    @DisplayName("1. シード（module_level_availability / module_definitions）")
    class Seed {

        @Test
        @DisplayName("payment は ORGANIZATION=1 / TEAM=1 / PERSONAL=0")
        void paymentのレベル別可否が期待どおり() throws Exception {
            Map<String, Integer> actual = readPaymentAvailability();

            assertThat(actual)
                    .as("payment × 3レベルの行が存在すること")
                    .hasSize(3);
            assertThat(actual.get("ORGANIZATION"))
                    .as("組織の支払い機能は全層で実装済みのため ORGANIZATION は有効")
                    .isEqualTo(1);
            assertThat(actual.get("TEAM")).as("TEAM は従来どおり有効").isEqualTo(1);
            assertThat(actual.get("PERSONAL")).as("PERSONAL は対象外").isEqualTo(0);
        }

        @Test
        @DisplayName("payment は requires_paid_plan=0（無料解放）・is_active=1・OPTIONAL")
        void paymentは無料モジュールである() throws Exception {
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT requires_paid_plan, is_active, module_type "
                                 + "FROM module_definitions WHERE slug = ?")) {
                ps.setString(1, PAYMENT_SLUG);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("payment の定義行が存在すること").isTrue();
                    assertThat(rs.getInt("requires_paid_plan"))
                            .as("無料機能として解放するため有料プラン判定（TMPL_004）に掛からないこと")
                            .isZero();
                    assertThat(rs.getInt("is_active")).isEqualTo(1);
                    assertThat(rs.getString("module_type")).isEqualTo("OPTIONAL");
                }
            }
        }
    }

    @Nested
    @DisplayName("2. ModuleService.toggleOrganizationModule（TMPL_005 を投げない）")
    class ToggleWithRealSeed {

        @Test
        @DisplayName("実DBのレベル可否を使って組織で payment を有効化しても TMPL_005 にならない")
        void 組織でpaymentを有効化できる() throws Exception {
            // Given: 実 DB（Flyway 適用後）から payment の module_id とレベル可否を読み出す
            long moduleId = readPaymentModuleId();
            boolean orgAvailable = readPaymentAvailability().get("ORGANIZATION") == 1;

            ModuleDefinitionEntity payment = ModuleDefinitionEntity.builder()
                    .name("決済")
                    .slug(PAYMENT_SLUG)
                    .description("オンライン決済・集金")
                    .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                    .moduleNumber(30)
                    .requiresPaidPlan(false)
                    .trialDays(14)
                    .isActive(true)
                    .build();

            ModuleDefinitionRepository moduleDefinitionRepository = mock(ModuleDefinitionRepository.class);
            ModuleLevelAvailabilityRepository moduleLevelAvailabilityRepository =
                    mock(ModuleLevelAvailabilityRepository.class);
            OrganizationEnabledModuleRepository organizationEnabledModuleRepository =
                    mock(OrganizationEnabledModuleRepository.class);

            ModuleService moduleService = new ModuleService(
                    moduleDefinitionRepository,
                    moduleLevelAvailabilityRepository,
                    mock(ModuleRecommendationRepository.class),
                    mock(TeamEnabledModuleRepository.class),
                    organizationEnabledModuleRepository,
                    mock(TemplateModuleRepository.class),
                    mock(TeamTemplateRepository.class),
                    mock(TeamPlanService.class),
                    mock(EntitlementQueryService.class));

            given(moduleDefinitionRepository.findById(moduleId)).willReturn(Optional.of(payment));
            // 実 DB から読んだ is_available をそのままレベル可否として食わせる（シードが判定を支配する）
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    payment.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.of(ModuleLevelAvailabilityEntity.builder()
                            .moduleId(moduleId)
                            .level(ModuleLevelAvailabilityEntity.Level.ORGANIZATION)
                            .isAvailable(orgAvailable)
                            .build()));
            given(organizationEnabledModuleRepository
                    .countByOrganizationIdAndIsEnabledTrueAndIsGrandfatheredFalse(ORG_ID))
                    .willReturn(0L);
            given(organizationEnabledModuleRepository.findByOrganizationIdAndModuleId(ORG_ID, moduleId))
                    .willReturn(Optional.empty());
            given(organizationEnabledModuleRepository.save(any(OrganizationEnabledModuleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When / Then: TMPL_005（このレベルでは利用できません）が投げられないこと
            assertThatCode(() -> moduleService.toggleOrganizationModule(
                    ORG_ID, new ToggleModuleRequest(moduleId, true), USER_ID))
                    .as("payment は ORGANIZATION で利用可能なので例外は発生しない")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("判定機構の健全性: レベル可否が 0 なら TMPL_005 になる")
        void レベル可否が0なら例外になる() {
            ModuleDefinitionEntity payment = ModuleDefinitionEntity.builder()
                    .name("決済")
                    .slug(PAYMENT_SLUG)
                    .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                    .moduleNumber(30)
                    .requiresPaidPlan(false)
                    .trialDays(14)
                    .isActive(true)
                    .build();

            ModuleDefinitionRepository moduleDefinitionRepository = mock(ModuleDefinitionRepository.class);
            ModuleLevelAvailabilityRepository moduleLevelAvailabilityRepository =
                    mock(ModuleLevelAvailabilityRepository.class);

            ModuleService moduleService = new ModuleService(
                    moduleDefinitionRepository,
                    moduleLevelAvailabilityRepository,
                    mock(ModuleRecommendationRepository.class),
                    mock(TeamEnabledModuleRepository.class),
                    mock(OrganizationEnabledModuleRepository.class),
                    mock(TemplateModuleRepository.class),
                    mock(TeamTemplateRepository.class),
                    mock(TeamPlanService.class),
                    mock(EntitlementQueryService.class));

            given(moduleDefinitionRepository.findById(30L)).willReturn(Optional.of(payment));
            given(moduleLevelAvailabilityRepository.findByModuleIdAndLevel(
                    payment.getId(), ModuleLevelAvailabilityEntity.Level.ORGANIZATION))
                    .willReturn(Optional.of(ModuleLevelAvailabilityEntity.builder()
                            .moduleId(30L)
                            .level(ModuleLevelAvailabilityEntity.Level.ORGANIZATION)
                            .isAvailable(false)
                            .build()));

            assertThatThrownBy(() -> moduleService.toggleOrganizationModule(
                    ORG_ID, new ToggleModuleRequest(30L, true), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TemplateErrorCode.TMPL_005);
        }
    }
}
