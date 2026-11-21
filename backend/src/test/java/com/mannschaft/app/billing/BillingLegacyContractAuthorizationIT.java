package com.mannschaft.app.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260930-1931 AC17/18: 実Security filter・Guard・MySQLによるlegacy HTTP認可の試練。
 * 内部Bean/Repository/人数を差し替えず、実SQLをStatementInspectorで観測する。
 * fixtureはRepositoryでcommitし、拒否後は別TXで再読込する。自動rollbackで副作用0を作らない。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(BillingLegacyContractAuthorizationIT.SqlObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("1931 legacy catalogと契約の実認可")
class BillingLegacyContractAuthorizationIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private PermissionRepository permissions;
    @Autowired private PermissionGroupRepository groups;
    @Autowired private PermissionGroupPermissionRepository groupPermissions;
    @Autowired private UserPermissionGroupRepository userGroups;
    @Autowired private PlanRepository plans;
    @Autowired private FeatureCatalogRepository features;
    @Autowired private PlanFeatureRepository planFeatures;
    @Autowired private EntitlementRepository entitlements;
    @Autowired private BillingPriceVersionRepository versions;
    @Autowired private BillingPriceBandVersionRepository bands;
    @Autowired private BillingContractRepository contracts;
    @PersistenceContext private EntityManager entityManager;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> organizationIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();
    private final List<Long> createdPermissionIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();
    private final List<UUID> versionIds = new ArrayList<>();
    private final List<UUID> bandIds = new ArrayList<>();
    private final List<String> planKeys = new ArrayList<>();
    private Long owner;
    private Long stranger;
    private String featureKey;
    private String freePlan;
    private String otherFreePlan;
    private String paidPlan;
    private UUID activePrice;
    private UUID scheduledPrice;

    @BeforeEach
    void 所有fixtureをcommitする() {
        transactions.executeWithoutResult(tx -> {
            owner = createUser();
            stranger = createUser();
            featureKey = "i1931.auth." + UUID.randomUUID();
            features.saveAndFlush(FeatureCatalogEntity.builder().featureKey(featureKey).category(FeatureCategory.INTERNAL)
                    .addonAvailable(false).freeForNonprofit(false).displayNameKey("1931-auth")
                    .descriptionKey("1931-auth").sortOrder(0).enabled(true).build());
            freePlan = createPlan(0);
            otherFreePlan = createPlan(0);
            paidPlan = createPlan(1_100);
            Instant due = Instant.now().minusSeconds(3_600);
            activePrice = price(BillingPriceVersionStatus.ACTIVE, due.minusSeconds(86_400), due, 2_200L, 1L);
            scheduledPrice = price(BillingPriceVersionStatus.SCHEDULED, due, null, 3_630L, 2L);
        });
    }

    @AfterEach
    void 所有fixtureだけを後始末する() {
        SqlObservation.stop();
        transactions.executeWithoutResult(tx -> {
            // scope数値だけで限定するとUSER/TEAM/ORGのIDが衝突する。所有作成者とplanを併用する。
            List<UUID> ids = entityManager.createQuery(
                    "SELECT c.id FROM BillingContractEntity c WHERE c.createdBy IN :users AND c.planKey IN :plans",
                    UUID.class).setParameter("users", userIds).setParameter("plans", planKeys).getResultList();
            if (!ids.isEmpty()) {
                delete("DELETE FROM entitlements WHERE source_ref_id IN (:ids)", ids);
                for (String table : List.of("active_contract_pointers", "active_billing_contract_operation_pointers",
                        "billing_contract_operations")) {
                    delete("DELETE FROM " + table + " WHERE contract_id IN (:ids)", ids);
                }
                delete("DELETE FROM billing_contracts WHERE id IN (:ids)", ids);
            }
            delete("DELETE FROM user_permission_groups WHERE user_id IN (:ids)", userIds);
            if (!groupIds.isEmpty()) {
                delete("DELETE FROM permission_group_permissions WHERE group_id IN (:ids)", groupIds);
                delete("DELETE FROM permission_groups WHERE id IN (:ids)", groupIds);
            }
            delete("DELETE FROM user_roles WHERE user_id IN (:ids)", userIds);
            bands.deleteAllById(bandIds);
            bands.flush();
            versions.deleteAllById(versionIds);
            versions.flush();
            delete("DELETE FROM plan_features WHERE plan_key IN (:ids)", planKeys);
            plans.deleteAllById(planKeys);
            plans.flush();
            features.deleteById(featureKey);
            teams.deleteAllById(teamIds);
            teams.flush();
            organizations.deleteAllById(organizationIds);
            organizations.flush();
            users.deleteAllById(userIds);
            permissions.deleteAllById(createdPermissionIds);
            roles.deleteAllById(createdRoleIds);
        });
    }

    @Test
    void AC17_未認証catalogは401で価格SQLとdue昇格が0() throws Exception {
        List<String> statements = SqlObservation.start();
        try {
            mvc.perform(get("/api/v1/billing/plans")).andExpect(status().isUnauthorized());
        } finally {
            SqlObservation.stop();
        }
        assertThat(priceSql(statements)).isEmpty();
        assertPricesUnchanged();
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"MEMBER", "ADMIN"})
    void AC17_認証会員は所属org無しでもglobalCatalogを閲覧できる(String roleName) throws Exception {
        Long team = transactions.execute(tx -> createScope(EntitlementScopeKind.TEAM));
        transactions.executeWithoutResult(tx -> assignRole(owner, EntitlementScopeKind.TEAM, team, roleName));
        List<String> statements = SqlObservation.start();
        MvcResult result;
        try {
            result = mvc.perform(get("/api/v1/billing/plans").with(user(owner.toString()).roles(roleName)))
                    .andExpect(status().isOk()).andReturn();
        } finally {
            SqlObservation.stop();
        }
        JsonNode returnedPlans = body(result).path("data").path("plans");
        assertThat(returnedPlans.isArray()).isTrue();
        List<String> returnedKeys = new ArrayList<>();
        returnedPlans.forEach(plan -> returnedKeys.add(plan.path("planKey").asText()));
        assertThat(returnedKeys).contains(freePlan, otherFreePlan, paidPlan);
        // 未認証の0 SQLが計測器の空振りでないことを同じ実入口で確かめる。
        assertThat(priceSql(statements)).isNotEmpty();
    }

    @Test
    void AC18_USER本人は作成変更でき他人の契約IDは404() throws Exception {
        UUID id = createContract(owner, "/api/v1/me/billing/contracts");
        assertRejected(put("/api/v1/me/billing/contracts/" + id).with(user(stranger.toString()))
                .content(changeBody(paidPlan)), 404, "ENTITLEMENT_007");
        mvc.perform(put("/api/v1/me/billing/contracts/" + id).with(user(owner.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content(changeBody(otherFreePlan)))
                .andExpect(status().isOk());
        assertChanged(id, EntitlementScopeKind.USER, owner);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"TEAM,ADMIN", "TEAM,DEPUTY_ADMIN", "ORG,ADMIN", "ORG,DEPUTY_ADMIN"})
    void AC18_ADMINと課金委任DEPUTYは実DB権限で作成変更できる(
            EntitlementScopeKind scope, String roleName) throws Exception {
        Long scopeId = transactions.execute(tx -> createScope(scope));
        transactions.executeWithoutResult(tx -> {
            assignRole(owner, scope, scopeId, roleName);
            if ("DEPUTY_ADMIN".equals(roleName)) {
                grantBilling(owner, scope, scopeId);
            }
        });
        String path = contractPath(scope, scopeId);
        UUID id = createContract(owner, path);
        mvc.perform(put(path + "/" + id).with(user(owner.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content(changeBody(otherFreePlan)))
                .andExpect(status().isOk());
        assertChanged(id, scope, scopeId);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"TEAM,MEMBER", "TEAM,DEPUTY_ADMIN", "ORG,MEMBER", "ORG,DEPUTY_ADMIN"})
    void AC18_MEMBERと未委任DEPUTYは403で価格契約権利の副作用0(
            EntitlementScopeKind scope, String roleName) throws Exception {
        Long scopeId = transactions.execute(tx -> createScope(scope));
        transactions.executeWithoutResult(tx -> {
            assignRole(owner, scope, scopeId, "ADMIN");
            assignRole(stranger, scope, scopeId, roleName);
        });
        String path = contractPath(scope, scopeId);
        UUID id = createContract(owner, path);
        assertRejected(post(path).with(user(stranger.toString()))
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(createBody(paidPlan)), 403, null);
        assertRejected(put(path + "/" + id).with(user(stranger.toString()))
                .content(changeBody(paidPlan)), 403, null);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"TEAM", "ORG"})
    void AC18_他tenantADMINは403で許可scopeへの他契約ID差込は404(EntitlementScopeKind scope) throws Exception {
        Long ownScope = transactions.execute(tx -> createScope(scope));
        Long foreignScope = transactions.execute(tx -> createScope(scope));
        transactions.executeWithoutResult(tx -> {
            assignRole(owner, scope, ownScope, "ADMIN");
            assignRole(stranger, scope, foreignScope, "ADMIN");
            grantBilling(stranger, scope, foreignScope);
        });
        String path = contractPath(scope, ownScope);
        UUID id = createContract(owner, path);
        assertRejected(post(path).with(user(stranger.toString()).roles("SYSTEM_ADMIN"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).content(createBody(paidPlan)), 403, null);
        assertRejected(put(path + "/" + id).with(user(stranger.toString()))
                .content(changeBody(paidPlan)), 403, null);
        assertRejected(put(contractPath(scope, foreignScope) + "/" + id).with(user(stranger.toString()))
                .content(changeBody(paidPlan)), 404, "ENTITLEMENT_007");
    }

    private void assertRejected(MockHttpServletRequestBuilder request, int code, String errorCode) throws Exception {
        List<String> before = snapshot();
        List<String> statements = SqlObservation.start();
        MvcResult result;
        try {
            result = mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(status().is(code)).andReturn();
        } finally {
            SqlObservation.stop();
        }
        if (errorCode != null) {
            assertThat(body(result).path("error").path("code").asText()).isEqualTo(errorCode);
        }
        assertThat(priceSql(statements)).isEmpty();
        assertThat(statements).noneMatch(sql -> (sql.startsWith("insert ") || sql.startsWith("update ")
                || sql.startsWith("delete ")) && (sql.contains("billing_contract") || sql.contains("contract_pointer")
                || sql.contains("entitlements")));
        assertThat(snapshot()).containsExactlyElementsOf(before);
        assertPricesUnchanged();
    }

    private UUID createContract(Long actor, String path) throws Exception {
        MvcResult result = mvc.perform(post(path).with(user(actor.toString()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(freePlan)))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(body(result).path("data").path("contractId").asText());
        transactions.executeWithoutResult(tx -> {
            entityManager.clear();
            assertThat(contracts.findById(id).orElseThrow().getPlanKey()).isEqualTo(freePlan);
            assertThat(entitlements.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(EntitlementSourceKind.PLAN, id))
                    .singleElement().satisfies(entitlement -> assertThat(entitlement.getFeatureKey()).isEqualTo(featureKey));
        });
        return id;
    }

    private void assertChanged(UUID original, EntitlementScopeKind scope, Long scopeId) {
        transactions.executeWithoutResult(tx -> {
            entityManager.clear();
            assertThat(contracts.findById(original).orElseThrow().getStatus()).isEqualTo(ContractStatus.CANCELLED);
            assertThat(entityManager.createQuery("SELECT c FROM BillingContractEntity c WHERE c.scopeKind = :scope "
                            + "AND c.scopeId = :id AND c.planKey = :plan AND c.status = :status", BillingContractEntity.class)
                    .setParameter("scope", scope).setParameter("id", scopeId).setParameter("plan", otherFreePlan)
                    .setParameter("status", ContractStatus.ACTIVE).getResultList()).hasSize(1);
        });
    }

    /** 所有する契約の値とpointer/権利/operation件数を別TXで比較する。 */
    private List<String> snapshot() {
        return transactions.execute(tx -> {
            entityManager.clear();
            List<String> state = new ArrayList<>();
            List<BillingContractEntity> owned = entityManager.createQuery(
                    "SELECT c FROM BillingContractEntity c WHERE c.createdBy IN :users AND c.planKey IN :plans ORDER BY c.id",
                    BillingContractEntity.class).setParameter("users", userIds).setParameter("plans", planKeys).getResultList();
            for (BillingContractEntity c : owned) {
                state.add(c.getId() + ":" + c.getStatus() + ":" + c.getPlanKey() + ":" + c.getCancelledAt() + ":"
                        + c.getPriceJpySnapshot() + ":" + c.getMemberCountSnapshot() + ":" + c.getBandNoSnapshot());
                for (String table : List.of("active_contract_pointers", "active_billing_contract_operation_pointers",
                        "billing_contract_operations")) {
                    state.add(table + ":" + entityManager.createNativeQuery("SELECT COUNT(*) FROM " + table
                            + " WHERE contract_id = :id").setParameter("id", c.getId()).getSingleResult());
                }
                entityManager.createQuery("SELECT e FROM EntitlementEntity e WHERE e.sourceRefId = :id ORDER BY e.id",
                                EntitlementEntity.class).setParameter("id", c.getId()).getResultList()
                        .forEach(e -> state.add("entitlement:" + e.getId() + ":" + e.getFeatureKey() + ":"
                                + e.getValidFrom() + ":" + e.getValidUntil() + ":" + e.getRevokedAt()));
            }
            return state;
        });
    }

    private void assertPricesUnchanged() {
        transactions.executeWithoutResult(tx -> {
            entityManager.clear();
            assertThat(versions.findById(activePrice).orElseThrow().getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);
            assertThat(versions.findById(scheduledPrice).orElseThrow().getStatus()).isEqualTo(BillingPriceVersionStatus.SCHEDULED);
            assertThat(bands.findAllById(bandIds)).extracting(BillingPriceBandVersionEntity::getStatus)
                    .containsExactlyInAnyOrder(BillingPriceVersionStatus.ACTIVE, BillingPriceVersionStatus.SCHEDULED);
        });
    }

    private Long createUser() {
        Long id = users.saveAndFlush(UserEntity.builder().email("1931-auth-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("認可").displayName("認可試練").status(UserEntity.UserStatus.ACTIVE)
                .locale("ja").timezone("Asia/Tokyo").isSearchable(true).build()).getId();
        userIds.add(id);
        return id;
    }

    private Long createScope(EntitlementScopeKind scope) {
        String slug = "i1931a-" + UUID.randomUUID().toString().substring(0, 16);
        if (scope == EntitlementScopeKind.TEAM) {
            Long id = teams.saveAndFlush(TeamEntity.builder().slug(slug).name(slug)
                    .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
            teamIds.add(id);
            return id;
        }
        Long id = organizations.saveAndFlush(OrganizationEntity.builder().slug(slug).name(slug)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        organizationIds.add(id);
        return id;
    }

    private void assignRole(Long actor, EntitlementScopeKind scope, Long scopeId, String roleName) {
        RoleEntity role = roles.findByName(roleName).orElseGet(() -> {
            RoleEntity created = roles.saveAndFlush(RoleEntity.builder().name(roleName).displayName(roleName)
                    .priority(1).isSystem(false).build());
            createdRoleIds.add(created.getId());
            return created;
        });
        userRoles.saveAndFlush(UserRoleEntity.builder().userId(actor).roleId(role.getId())
                .teamId(scope == EntitlementScopeKind.TEAM ? scopeId : null)
                .organizationId(scope == EntitlementScopeKind.ORG ? scopeId : null).grantedBy(owner).build());
    }

    private void grantBilling(Long actor, EntitlementScopeKind scope, Long scopeId) {
        String name = scope == EntitlementScopeKind.TEAM ? "MANAGE_TEAM_BILLING" : "MANAGE_ORGANIZATION_BILLING";
        PermissionEntity permission = permissions.findByNameIn(List.of(name)).stream().findFirst().orElseGet(() -> {
            PermissionEntity created = permissions.saveAndFlush(PermissionEntity.builder().name(name).displayName(name)
                    .scope(scope == EntitlementScopeKind.TEAM ? PermissionEntity.Scope.TEAM : PermissionEntity.Scope.ORGANIZATION)
                    .build());
            createdPermissionIds.add(created.getId());
            return created;
        });
        Long group = groups.saveAndFlush(PermissionGroupEntity.builder().name("1931-auth-" + UUID.randomUUID())
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN).createdBy(owner)
                .teamId(scope == EntitlementScopeKind.TEAM ? scopeId : null)
                .organizationId(scope == EntitlementScopeKind.ORG ? scopeId : null).build()).getId();
        groupIds.add(group);
        groupPermissions.saveAndFlush(PermissionGroupPermissionEntity.builder().groupId(group)
                .permissionId(permission.getId()).build());
        userGroups.saveAndFlush(UserPermissionGroupEntity.builder().groupId(group).userId(actor).assignedBy(owner).build());
    }

    private String createPlan(int amount) {
        String key = "I1931A-" + UUID.randomUUID().toString().substring(0, 16);
        plans.saveAndFlush(PlanEntity.builder().planKey(key).baseMonthlyPriceJpy(amount).displayNameKey("1931-auth")
                .descriptionKey("1931-auth").sortOrder(0).enabled(true).build());
        planFeatures.saveAndFlush(PlanFeatureEntity.builder().planKey(key).featureKey(featureKey).build());
        planKeys.add(key);
        return key;
    }

    private UUID price(BillingPriceVersionStatus status, Instant from, Instant until, long amount, long revision) {
        UUID id = versions.saveAndFlush(BillingPriceVersionEntity.builder().productKind(BillingProductKind.PLAN)
                .productKey(paidPlan).scopeKind(EntitlementScopeKind.USER).catalogRevision("1931-auth-" + revision)
                .revisionNo(revision).status(status).effectiveFrom(from).effectiveUntil(until).provisionAttempts(0)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build()).getId();
        versionIds.add(id);
        bandIds.add(bands.saveAndFlush(BillingPriceBandVersionEntity.builder().productKind(BillingProductKind.PLAN)
                .productKey(paidPlan).scopeKind(EntitlementScopeKind.USER).priceVersionId(id).bandNo(1).minMembers(1)
                .currency("JPY").stripePriceRef("price_1931auth_" + UUID.randomUUID()).inputAmount(amount)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountIncludingTax(amount).amountExcludingTax(amount / 11 * 10).taxAmount(amount / 11)
                .taxRateBasisPoints(1_000).taxNameSnapshot("消費税").includedInPrice(true)
                .effectiveFrom(from).effectiveUntil(until).status(status)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build()).getId());
        return id;
    }

    private String contractPath(EntitlementScopeKind scope, Long scopeId) {
        return "/api/v1/" + (scope == EntitlementScopeKind.TEAM ? "teams/" : "organizations/")
                + scopeId + "/billing/contracts";
    }

    private String createBody(String plan) {
        return "{\"contractKind\":\"PLAN\",\"planKey\":\"" + plan + "\"}";
    }

    private String changeBody(String plan) {
        return "{\"planKey\":\"" + plan + "\"}";
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private void delete(String sql, List<?> ids) {
        entityManager.createNativeQuery(sql).setParameter("ids", ids).executeUpdate();
    }

    private List<String> priceSql(List<String> sql) {
        return sql.stream().filter(statement -> statement.contains("billing_price_versions")
                || statement.contains("billing_price_band_versions") || statement.contains("plan_price_bands")).toList();
    }

    /** 実DataSource・Repositoryの結果を変更せず、JPAの実SQLだけ記録する。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class SqlObservationConfiguration {
        @Bean
        HibernatePropertiesCustomizer legacyAuthorizationSqlObservation() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", new SqlObservation());
        }
    }

    /** 要求スレッドだけの有限観測。fixture/検証SQLは観測区間外。 */
    static class SqlObservation implements StatementInspector {
        private static final ThreadLocal<List<String>> CURRENT = new ThreadLocal<>();

        static List<String> start() {
            List<String> statements = new ArrayList<>();
            CURRENT.set(statements);
            return statements;
        }

        static void stop() {
            CURRENT.remove();
        }

        @Override
        public String inspect(String sql) {
            List<String> statements = CURRENT.get();
            if (statements != null) {
                statements.add(sql.toLowerCase(Locale.ROOT));
            }
            return sql;
        }
    }
}
