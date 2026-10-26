package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.billing.ActiveContractPointerRepository;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.FeatureCategory;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.api.dto.ContractResponse;
import com.mannschaft.app.billing.api.dto.CreateContractRequest;
import com.mannschaft.app.billing.beta.BetaGrantEntity;
import com.mannschaft.app.billing.beta.BetaGrantRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 3-D（設計書 §9.2 #17）: TEAM スコープの契約・特典の「代表親組織」の記録と、
 * SYSTEM_ADMIN による組織の明示選択の受け入れテスト（試練）。
 *
 * <ul>
 *   <li>AC-N13: T の TEAM 契約を作成すると契約行に代表親組織（§9.3: 最初に成立した ACTIVE 加盟）が記録され、
 *       その後 T が別の組織へ加盟しても契約の組織は変わらない。</li>
 *   <li>AC-G126: SYSTEM_ADMIN は手動付与（契約・ベータ特典）で TEAM スコープの組織を明示して選べる。</li>
 * </ul>
 *
 * <p><b>フィクスチャの狙い</b>: 加盟行の挿入順（行 ID 順）と「最初に成立した加盟」を食い違わせてある。
 * 旧実装の {@code findByTeamIdAndStatus} の先頭（並び順不定＝実質は行 ID 順）では代表親組織にならない。
 * 後から加盟する組織 C は応答日時が最も古く ID も最小にして、再解決すると結果が反転する形にした。</p>
 *
 * <p>Stripe の境界（{@code BillingPaymentGateway} 等）は、無償プラン（月額 0 円）だけを使うため呼ばれない。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 3-D 課金: 代表親組織の記録と SYSTEM_ADMIN の組織選択（AC-N13/G126）")
class BillingTeamParentOrganizationIT extends AbstractMySqlIntegrationTest {

    private static final String FREE_PLAN = "FREE";
    private static final String FULL_PLAN = "FULL";
    private static final String FEATURE_KEY = "ads.hide";
    private static final long SYSADMIN_ID = 9_100_001L;
    private static final long TEAM_OPERATOR_ID = 9_100_002L;

    /** テストごとに衝突しない ID（実 DB はロールバックしない）。 */
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L + 5_000_000_000L);

    @Autowired private MockMvc mockMvc;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractApplicationService contractApplicationService;
    @Autowired private TeamOrgMembershipRepository membershipRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private PlanFeatureRepository planFeatureRepository;
    @Autowired private FeatureCatalogRepository featureCatalogRepository;
    @Autowired private BetaGrantRepository betaGrantRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;
    @Autowired private BillingCheckoutContractRepository checkoutContractRepository;
    @Autowired private ActiveContractPointerRepository activeContractPointerRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long teamId;
    /** 最初に成立した加盟（代表親組織）。ID は B より大きい。 */
    private long orgA;
    /** 2番目に成立した加盟。行は A より先に挿入する。 */
    private long orgB;

    @BeforeEach
    void seed() {
        seedPlan(FREE_PLAN, 0);
        seedPlan(FULL_PLAN, 0);
        planFeatureRepository.save(PlanFeatureEntity.builder().planKey(FULL_PLAN).featureKey(FEATURE_KEY).build());
        if (!featureCatalogRepository.existsById(FEATURE_KEY)) {
            featureCatalogRepository.save(FeatureCatalogEntity.builder()
                    .featureKey(FEATURE_KEY).category(FeatureCategory.INTERNAL)
                    .addonAvailable(Boolean.FALSE).freeForNonprofit(Boolean.FALSE)
                    .displayNameKey("feature." + FEATURE_KEY + ".name")
                    .descriptionKey("feature." + FEATURE_KEY + ".desc")
                    .sortOrder(0).enabled(Boolean.TRUE).build());
        }

        teamId = SEQ.incrementAndGet();
        long base = SEQ.addAndGet(10);
        orgB = base;          // ID は小さいが、加盟は後
        orgA = base + 1;      // ID は大きいが、最初に成立した加盟
        LocalDateTime t0 = LocalDateTime.now().minusDays(30).withNano(0);
        // 行 ID 順は B → A（旧実装の「先頭」は B になる）。応答日時は A が先。
        affiliate(teamId, orgB, t0.plusDays(10));
        affiliate(teamId, orgA, t0);
    }

    // ═════════ AC-N13 ═════════

    @Test
    @DisplayName("AC-N13: チーム契約の作成で契約行に代表親組織（最初に成立した加盟）が記録され、後の加盟で変わらない")
    void AC_N13_契約行に代表親組織が記録され後の加盟で変わらない() {
        // 契約変更 tx は操作者の現在権限を再確認する（BillingOperationAuthorizer）ため、実在するチーム ADMIN を用意する。
        long adminId = insertTeamAdmin(teamId);
        ContractResponse created = contractApplicationService.create(
                EntitlementScopeKind.TEAM, teamId, adminId,
                new CreateContractRequest("PLAN", FREE_PLAN, null), UUID.randomUUID().toString());

        UUID contractId = UUID.fromString(created.getContractId());
        assertThat(contractOrganizationId(contractId))
                .as("作成時に §9.3 の代表親組織（最初に成立した ACTIVE 加盟）を契約行へ記録する")
                .isEqualTo(orgA);

        // 応答日時が最も古く ID も最小の組織 C へ後から加盟する。再解決すると代表親組織が C に反転する形。
        long orgC = orgB - 1_000;
        affiliate(teamId, orgC, LocalDateTime.now().minusDays(365).withNano(0));

        assertThat(contractOrganizationId(contractId))
                .as("その後に加盟先が増えても、契約行の組織は変わらない（再解決しない）")
                .isEqualTo(orgA);
    }

    @Test
    @DisplayName("AC-N13: SYSTEM_ADMIN の手動付与で組織を指定しないとき、代表親組織が契約行に記録される")
    void AC_N13_手動付与で組織未指定なら代表親組織が記録される() throws Exception {
        MvcResult result = grantContract(teamId, null).andExpect(status().isCreated()).andReturn();

        assertThat(contractOrganizationId(contractIdOf(result)))
                .as("組織未指定の手動付与も §9.3 の代表親組織（旧実装は並び順不定の先頭）")
                .isEqualTo(orgA);
    }

    @Test
    @DisplayName("AC-N13: quote→Checkout の有料契約（PENDING 起票）でも契約行・pointer に代表親組織が記録され、後の加盟で変わらない")
    void AC_N13_Checkout経路でも代表親組織が記録される() {
        BillingMoney money = new BillingMoney("JPY", 1_100L, 1_000L, 100L, "消費税", 1_000);
        Instant now = Instant.now();
        BillingQuoteSnapshot quote = new BillingQuoteSnapshot(
                UUID.randomUUID(), TEAM_OPERATOR_ID, EntitlementScopeKind.TEAM, teamId,
                UUID.randomUUID(), BillingProductKind.PLAN, FULL_PLAN, null, "price_dummy",
                5, money, money, "{}", now, now.plusSeconds(86_400), now,
                0L, "0".repeat(64), now.plusSeconds(900), null, 0L);

        UUID contractId = checkoutContractRepository.reservePendingContract(quote, TEAM_OPERATOR_ID);

        assertThat(contractOrganizationId(contractId))
                .as("Checkout 経路（旧実装は ORG 以外 null）も §9.3 の代表親組織を契約行へ記録する")
                .isEqualTo(orgA);
        assertThat(activeContractPointerRepository
                .findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                        EntitlementScopeKind.TEAM, teamId, ContractKind.PLAN, "")
                .orElseThrow().getOrganizationId())
                .as("アクティブ契約 pointer にも同じ組織を記録する")
                .isEqualTo(orgA);

        affiliate(teamId, orgB - 1_000, LocalDateTime.now().minusDays(365).withNano(0));
        assertThat(contractOrganizationId(contractId)).as("後の加盟で変わらない").isEqualTo(orgA);
    }

    // ═════════ AC-G126 ═════════

    @Test
    @DisplayName("AC-G126: SYSTEM_ADMIN は手動付与で TEAM スコープの組織を明示して選べ、選んだ組織が契約行に記録される")
    void AC_G126_手動付与で組織を明示できる() throws Exception {
        MvcResult result = grantContract(teamId, orgB).andExpect(status().isCreated()).andReturn();

        assertThat(contractOrganizationId(contractIdOf(result)))
                .as("代表親組織 A ではなく、明示した B が記録される")
                .isEqualTo(orgB);
    }

    @Test
    @DisplayName("AC-G126: そのチームの ACTIVE な親組織でない組織は選べない（400 ENTITLEMENT_041）")
    void AC_G126_親組織でない組織は選べない() throws Exception {
        grantContract(teamId, orgA + 777_777L)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_041"));
    }

    @Test
    @DisplayName("AC-G126: TEAM 以外のスコープに organizationId を付けると 400 ENTITLEMENT_041")
    void AC_G126_TEAM以外のスコープでは組織を指定できない() throws Exception {
        String body = "{\"scopeKind\":\"USER\",\"scopeId\":" + SEQ.incrementAndGet()
                + ",\"contractKind\":\"PLAN\",\"planKey\":\"" + FREE_PLAN + "\",\"organizationId\":" + orgA + "}";
        mockMvc.perform(post("/api/v1/system-admin/billing/grants")
                        .with(user(String.valueOf(SYSADMIN_ID)).roles("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_041"));
    }

    @Test
    @DisplayName("AC-G126: SYSTEM_ADMIN 以外は手動付与できない（403）")
    void AC_G126_SYSTEM_ADMIN以外は403() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/billing/grants")
                        .with(user("42").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(teamId, orgB)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-G126: ベータ特典の手動付与でも TEAM スコープの組織を明示して選べる")
    void AC_G126_ベータ特典でも組織を明示できる() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants")
                        .with(user(String.valueOf(SYSADMIN_ID)).roles("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(betaBody(teamId, 1, orgB)))
                .andExpect(status().isCreated());

        BetaGrantEntity grant = betaGrantRepository
                .findByScopeKindAndScopeIdAndBetaPhase(EntitlementScopeKind.TEAM, teamId, 1).orElseThrow();
        assertThat(grant.getOrganizationId()).as("明示した B が記録される").isEqualTo(orgB);
    }

    @Test
    @DisplayName("AC-N13: ベータ特典の手動付与で組織を指定しないとき、代表親組織が記録される")
    void AC_N13_ベータ特典で組織未指定なら代表親組織() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/beta-perks/grants")
                        .with(user(String.valueOf(SYSADMIN_ID)).roles("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(betaBody(teamId, 2, null)))
                .andExpect(status().isCreated());

        BetaGrantEntity grant = betaGrantRepository
                .findByScopeKindAndScopeIdAndBetaPhase(EntitlementScopeKind.TEAM, teamId, 2).orElseThrow();
        assertThat(grant.getOrganizationId()).isEqualTo(orgA);
    }

    @Test
    @DisplayName("AC-G126: 画面で選べるよう、チームの親組織の候補と代表親組織を SYSTEM_ADMIN に返す")
    void AC_G126_親組織の候補一覧を返す() throws Exception {
        mockMvc.perform(get("/api/v1/system-admin/billing/teams/{teamId}/parent-organizations", teamId)
                        .with(user(String.valueOf(SYSADMIN_ID)).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationIds.length()").value(2))
                .andExpect(jsonPath("$.data.organizationIds[?(@ == " + orgA + ")]").exists())
                .andExpect(jsonPath("$.data.organizationIds[?(@ == " + orgB + ")]").exists())
                .andExpect(jsonPath("$.data.representativeOrganizationId").value(orgA));

        mockMvc.perform(get("/api/v1/system-admin/billing/teams/{teamId}/parent-organizations", teamId)
                        .with(user("42").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-G126: 候補一覧は FE が選べるよう組織名と slug も返す")
    void AC_G126_候補一覧は組織名とslugを返す() throws Exception {
        long t = SEQ.incrementAndGet();
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .slug("g126-" + t).name("選択肢組織" + t)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true).build());
        affiliate(t, org.getId(), LocalDateTime.now().minusDays(1).withNano(0));

        mockMvc.perform(get("/api/v1/system-admin/billing/teams/{teamId}/parent-organizations", t)
                        .with(user(String.valueOf(SYSADMIN_ID)).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizations.length()").value(1))
                .andExpect(jsonPath("$.data.organizations[0].organizationId").value(org.getId()))
                .andExpect(jsonPath("$.data.organizations[0].name").value("選択肢組織" + t))
                .andExpect(jsonPath("$.data.organizations[0].slug").value("g126-" + t));
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private ResultActions grantContract(long scopeTeamId, Long organizationId) throws Exception {
        return mockMvc.perform(post("/api/v1/system-admin/billing/grants")
                .with(user(String.valueOf(SYSADMIN_ID)).roles("SYSTEM_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(grantBody(scopeTeamId, organizationId)));
    }

    private static String grantBody(long scopeTeamId, Long organizationId) {
        return "{\"scopeKind\":\"TEAM\",\"scopeId\":" + scopeTeamId
                + ",\"contractKind\":\"PLAN\",\"planKey\":\"" + FREE_PLAN + "\""
                + (organizationId == null ? "" : ",\"organizationId\":" + organizationId) + "}";
    }

    private static String betaBody(long scopeTeamId, int phase, Long organizationId) {
        return "{\"grantKind\":\"TEAM_ORG\",\"betaPhase\":" + phase + ",\"scopeKind\":\"TEAM\",\"scopeId\":"
                + scopeTeamId + ",\"skipCriteriaCheck\":true"
                + (organizationId == null ? "" : ",\"organizationId\":" + organizationId) + "}";
    }

    private UUID contractIdOf(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return UUID.fromString(node.path("data").path("contractId").asText());
    }

    private Long contractOrganizationId(UUID contractId) {
        BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
        return contract.getOrganizationId();
    }

    /** 実在する ACTIVE ユーザーに、そのチームの ADMIN ロールを付ける。 */
    private long insertTeamAdmin(long team) {
        return transactionTemplate.execute(tx -> {
            UserEntity user = UserEntity.builder()
                    .email("g126-admin-" + System.nanoTime() + "@example.com")
                    .lastName("課金").firstName("管理").displayName("課金管理")
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build();
            entityManager.persist(user);
            var ids = entityManager.createNativeQuery("SELECT id FROM roles WHERE name = 'ADMIN'").getResultList();
            long roleId;
            if (ids.isEmpty()) {
                RoleEntity role = RoleEntity.builder().name("ADMIN").displayName("ADMIN")
                        .priority(1).isSystem(true).build();
                entityManager.persist(role);
                entityManager.flush();
                roleId = role.getId();
            } else {
                roleId = ((Number) ids.get(0)).longValue();
            }
            entityManager.persist(UserRoleEntity.builder().userId(user.getId()).roleId(roleId).teamId(team).build());
            entityManager.flush();
            return user.getId();
        });
    }

    private void seedPlan(String planKey, int baseMonthlyPriceJpy) {
        if (planRepository.existsById(planKey)) {
            return;
        }
        planRepository.save(PlanEntity.builder()
                .planKey(planKey)
                .displayNameKey("plan." + planKey + ".name")
                .descriptionKey("plan." + planKey + ".desc")
                .baseMonthlyPriceJpy(baseMonthlyPriceJpy)
                .sortOrder(0)
                .enabled(Boolean.TRUE)
                .build());
    }

    private void affiliate(long team, long org, LocalDateTime respondedAt) {
        membershipRepository.save(TeamOrgMembershipEntity.builder()
                .teamId(team)
                .organizationId(org)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(respondedAt.minusDays(1))
                .respondedAt(respondedAt)
                .build());
    }
}
