package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.billing.api.BillingContractApplicationService;
import com.mannschaft.app.billing.api.BillingCatalogQueryService;
import com.mannschaft.app.billing.api.BillingCurrentBandResolver;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.billing.api.BillingCustomerJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.billing.api.dto.CreateContractRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * CMP-260930-1931 AC12/14/27: 実Resolverで得たAと、後続実契約時のBのhandoff。
 * 同期hookは実Resolverの処理とTX復帰を終えた後にClockだけを進め、返値を変更しない。
 * 内部Bean/Guard/DBは実物。外部Stripe窓口だけstubし、実機成功の代用にはしない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(BillingLegacyContractHandoffIT.HandoffConfiguration.class)
@DisplayName("1931 legacy契約handoffと保存前拒否")
class BillingLegacyContractHandoffIT extends AbstractMySqlIntegrationTest {

    private static final Instant BOUNDARY = Instant.parse("2026-11-09T00:00:00Z");
    private static final int A_PRICE = 1_100;
    private static final int B_PRICE = 3_630;

    @Autowired private PlanRepository plans;
    @Autowired private PlanPriceBandRepository legacyBands;
    @Autowired private PlanFeatureRepository planFeatures;
    @Autowired private FeatureCatalogRepository features;
    @Autowired private BillingPriceVersionRepository versions;
    @Autowired private BillingPriceBandVersionRepository bands;
    @Autowired private BillingPriceResolver resolver;
    @Autowired private BillingPriceSelector selector;
    @Autowired private BillingCheckoutService checkout;
    @Autowired private BillingContractService contracts;
    @Autowired private BillingContractApplicationService application;
    @Autowired private BillingContractRepository contractRepository;
    @Autowired private ActiveContractPointerRepository pointers;
    @Autowired private EntitlementRepository entitlements;
    @Autowired private UserRepository users;
    @Autowired private TransactionTemplate transactions;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MembershipRepository memberships;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private ScopeMemberCountService memberCounts;
    @Autowired private BillingCatalogQueryService catalog;
    @Autowired private BillingCurrentBandResolver currentBands;
    @Autowired private BillingCustomerJpaRepository customers;
    @Autowired private BillingInvoiceJpaRepository invoices;
    @MockitoBean private BillingPaymentGateway gateway;
    @TestBean(name = "utcClock", methodName = "handoffClock") private Clock clock;

    private String planKey;
    private String featureKey;
    private Long actor;
    private EntitlementScopeKind ownedScope = EntitlementScopeKind.USER;
    private Long ownedScopeId;
    private Long teamId;
    private Long organizationId;
    private Long createdAdminRole;
    private UUID invoiceId;
    private UUID customerId;
    private final List<String> otherPlans = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> extraUsers = new ArrayList<>();
    private final List<Long> userRoleIds = new ArrayList<>();
    private final List<UUID> versionIds = new ArrayList<>();
    private final List<UUID> bandIds = new ArrayList<>();

    static Clock handoffClock() {
        return new HandoffClock(BOUNDARY.minusSeconds(1), ZoneOffset.UTC);
    }

    @BeforeEach
    void 所有actorと実Aと予約Bをcommitする() {
        time(BOUNDARY.minusSeconds(1));
        planKey = "I1931H-" + UUID.randomUUID().toString().substring(0, 16);
        featureKey = planKey.toLowerCase(java.util.Locale.ROOT) + ".grant";
        transactions.executeWithoutResult(tx -> {
            actor = users.saveAndFlush(UserEntity.builder()
                    .email("i1931-handoff-" + UUID.randomUUID() + "@example.com")
                    .lastName("試練").firstName("1931").displayName("1931handoff")
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build()).getId();
            plans.saveAndFlush(PlanEntity.builder().planKey(planKey).baseMonthlyPriceJpy(A_PRICE)
                    .displayNameKey("it1931").descriptionKey("it1931").sortOrder(0).enabled(true).build());
            features.saveAndFlush(FeatureCatalogEntity.builder().featureKey(featureKey)
                    .category(FeatureCategory.INTERNAL).addonAvailable(false).freeForNonprofit(false)
                    .displayNameKey("it1931").descriptionKey("it1931").sortOrder(0).enabled(true).build());
            planFeatures.saveAndFlush(PlanFeatureEntity.builder().planKey(planKey).featureKey(featureKey).build());
            savePrice(1L, BillingPriceVersionStatus.ACTIVE, BOUNDARY.minusSeconds(86_400), BOUNDARY,
                    A_PRICE, 1_000L, 100L);
            savePrice(2L, BillingPriceVersionStatus.SCHEDULED, BOUNDARY, null, B_PRICE, 3_300L, 330L);
        });
        ownedScopeId = actor;
        when(gateway.createSubscriptionCheckout(anyLong(), anyInt(), anyString(), any(UUID.class),
                anyString(), anyString())).thenReturn(new BillingPaymentGateway.CheckoutSessionInfo(
                        "cs_it1931_handoff", "https://example.invalid/1931-checkout"));
        clearInvocations(gateway);
    }

    @AfterEach
    void 所有契約とfixtureだけを撤去する() {
        ResolverReturnHook.CURRENT.remove();
        transactions.executeWithoutResult(tx -> {
            if (invoiceId != null) { invoices.deleteById(invoiceId); invoices.flush(); }
            if (customerId != null) { customers.deleteById(customerId); customers.flush(); }
            if (actor != null) {
                List<BillingContractEntity> owned = ownedContracts();
                for (BillingContractEntity contract : owned) {
                    entityManager.createQuery("delete from EntitlementEntity e where e.sourceRefId = :id")
                            .setParameter("id", contract.getId()).executeUpdate();
                    pointers.hardDeleteBySlotAndContractId(contract.getScopeKind(), contract.getScopeId(),
                            ContractKind.PLAN, "", contract.getId());
                }
                contractRepository.deleteAll(owned);
                contractRepository.flush();
            }
            bands.deleteAllById(bandIds);
            bands.flush();
            versions.deleteAllById(versionIds);
            versions.flush();
            if (planKey != null) {
                planFeatures.deleteAll(planFeatures.findByPlanKey(planKey));
                planFeatures.flush();
                for (PlanPriceBandScopeKind kind : PlanPriceBandScopeKind.values()) {
                    legacyBands.deleteAll(legacyBands.findByPlanKeyAndScopeKindOrderByBandNoAsc(planKey, kind));
                }
                legacyBands.flush();
                plans.deleteById(planKey);
                for (String other : otherPlans) {
                    planFeatures.deleteAll(planFeatures.findByPlanKey(other));
                    planFeatures.flush();
                    plans.deleteById(other);
                }
            }
            if (featureKey != null) features.deleteById(featureKey);
            userRoles.deleteAllById(userRoleIds);
            userRoles.flush();
            memberships.deleteAllById(memberIds);
            memberships.flush();
            users.deleteAllById(extraUsers);
            if (teamId != null) teams.deleteById(teamId);
            if (organizationId != null) organizations.deleteById(organizationId);
            if (createdAdminRole != null) roles.deleteById(createdAdminRole);
            if (actor != null) users.deleteById(actor);
        });
    }

    @Test
    void 実ResolverのAを渡した後で改定BになればCheckout保存前に019で拒否する() {
        Integer quoted = resolve();
        assertThat(quoted).isEqualTo(A_PRICE);
        time(BOUNDARY);

        assertError(() -> checkout.startPaidContract(EntitlementScopeKind.USER, actor, null,
                ContractKind.PLAN, planKey, null, quoted, actor), EntitlementErrorCode.PRICE_NOT_SELLABLE);

        assertNoContractSideEffects();
    }

    @Test
    void Applicationの実Resolver復帰後に改定境界を越えても旧AでPSPへ進めない() {
        AtomicReference<Object> observed = new AtomicReference<>();
        ResolverReturnHook.CURRENT.set(value -> {
            observed.set(value);
            time(BOUNDARY);
        });

        assertError(() -> application.create(EntitlementScopeKind.USER, actor, actor,
                new CreateContractRequest("PLAN", planKey, null), UUID.randomUUID().toString()),
                EntitlementErrorCode.PRICE_NOT_SELLABLE);

        assertThat(observed.get()).isEqualTo(A_PRICE);
        assertNoContractSideEffects();
    }

    @Test
    void Application無料判定の後に予約Bが開始したら017で権利発行前に拒否する() {
        removeCurrentPrice();
        transactions.executeWithoutResult(tx -> plans.findById(planKey).orElseThrow().setBaseMonthlyPriceJpy(0));
        assertThat(resolve()).isZero();
        AtomicReference<Object> observed = new AtomicReference<>();
        ResolverReturnHook.CURRENT.set(value -> {
            observed.set(value);
            time(BOUNDARY);
        });

        assertError(() -> application.create(EntitlementScopeKind.USER, actor, actor,
                new CreateContractRequest("PLAN", planKey, null), UUID.randomUUID().toString()),
                EntitlementErrorCode.CONTRACT_CHANGE_REQUIRES_PAYMENT);

        assertThat(observed.get()).isEqualTo(0);
        assertNoContractSideEffects();
    }

    @Test
    void 通常無料入口へ現在有料PLANを渡しても017で保存前に拒否する() {
        time(BOUNDARY);
        assertThat(selector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)
                .orElseThrow().bands().getFirst().getAmountIncludingTax()).isEqualTo((long) B_PRICE);

        assertError(() -> contracts.createContract(EntitlementScopeKind.USER, actor, null,
                ContractKind.PLAN, planKey, null, actor), EntitlementErrorCode.CONTRACT_CHANGE_REQUIRES_PAYMENT);

        assertNoContractSideEffects();
    }

    @Test
    void 旧有料だけで新公開価格がなければpaid入口も040で保存前に拒否する() {
        transactions.executeWithoutResult(tx -> {
            bands.deleteAllById(bandIds);
            bands.flush();
            versions.deleteAllById(versionIds);
            versions.flush();
        });

        assertError(() -> contracts.createPendingPaidContract(EntitlementScopeKind.USER, actor, null,
                ContractKind.PLAN, planKey, null, A_PRICE, actor), EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);

        assertNoContractSideEffects();
    }

    /** 初回greenが正常の契約characterization。USERはmapperのbandNoを呼ばずnullを維持する。 */
    @Test
    void USERの現在Bと一致するpaid契約は額Bとnull帯を保存しPSPへ一回進む() {
        time(BOUNDARY);
        BillingCheckoutService.PaidCheckoutResult result = checkout.startPaidContract(
                EntitlementScopeKind.USER, actor, null, ContractKind.PLAN, planKey, null, B_PRICE, actor);

        transactions.executeWithoutResult(tx -> {
            BillingContractEntity saved = contractRepository.findById(result.pending().contractId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ContractStatus.PENDING);
            assertThat(saved.getPriceJpySnapshot()).isEqualTo(B_PRICE);
            assertThat(saved.getBandNoSnapshot()).isNull();
            assertThat(saved.getMemberCountSnapshot()).isNull();
            assertThat(pointers.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                    EntitlementScopeKind.USER, actor, ContractKind.PLAN, "").orElseThrow().getContractId())
                    .isEqualTo(saved.getId());
            assertThat(entitlements.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                    EntitlementSourceKind.PLAN, saved.getId())).isEmpty();
        });
        assertThat(pspCreates()).isEqualTo(1);
    }

    /** SYSTEM_ADMINの専用Java入口は通常無料入口の価格guardを共有しない既存互換。 */
    @Test
    void SYSTEM_ADMIN手動付与は有料モデルでも従来のnull額と権利を保存する() {
        time(BOUNDARY);
        BillingContractService.ContractResult result = contracts.createContractBySystemAdmin(
                EntitlementScopeKind.USER, actor, null, ContractKind.PLAN, planKey, null, actor);

        transactions.executeWithoutResult(tx -> {
            BillingContractEntity saved = contractRepository.findById(result.contractId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ContractStatus.ACTIVE);
            assertThat(saved.getPriceJpySnapshot()).isNull();
            assertThat(entitlements.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                    EntitlementSourceKind.PLAN, saved.getId())).extracting(EntitlementEntity::getFeatureKey)
                    .containsExactly(featureKey);
        });
        assertThat(pspCreates()).isZero();
    }

    /** 既存互換のrollback校正。実pointer flush後の権利INSERTをMySQLが拒否する。 */
    @Test
    void 実DBが権利発行途中で失敗したら契約とpointerもrollbackする() {
        transactions.executeWithoutResult(tx -> {
            bands.deleteAllById(bandIds);
            bands.flush();
            versions.deleteAllById(versionIds);
            versions.flush();
            plans.findById(planKey).orElseThrow().setBaseMonthlyPriceJpy(0);
        });
        String trigger = "it1931h_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON entitlements FOR EACH ROW BEGIN "
                + "IF NEW.feature_key = '" + featureKey + "' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'it1931-owned-entitlement-failure'; END IF; END");
        try {
            assertThatThrownBy(() -> contracts.createContract(EntitlementScopeKind.USER, actor, null,
                    ContractKind.PLAN, planKey, null, actor)).isInstanceOf(DataAccessException.class);
            assertNoContractSideEffects();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    /** AC27: 実人数20で得たAを、別commitの21人目の後へ持ち越さない。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(value = EntitlementScopeKind.class, names = {"TEAM", "ORG"})
    void 実membership追加で帯が変わったら019で契約とPSPを増やさない(EntitlementScopeKind scope) {
        prepareMemberScope(scope, 20);
        assertThat(memberCounts.countActiveMembers(scope, ownedScopeId)).isEqualTo(20);
        Integer quoted = resolver.resolveMonthlyPriceJpy(scope, ownedScopeId, ContractKind.PLAN, planKey, null);
        assertThat(quoted).isEqualTo(A_PRICE);
        addMember();
        assertThat(memberCounts.countActiveMembers(scope, ownedScopeId)).isEqualTo(21);

        assertError(() -> checkout.startPaidContract(scope, ownedScopeId, organizationId,
                ContractKind.PLAN, planKey, null, quoted, actor), EntitlementErrorCode.PRICE_NOT_SELLABLE);

        assertNoContractSideEffects();
    }

    /** AC12: 額と人数と新帯Short1/10を同じ実選択から保存する。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"TEAM,1,1,1100", "TEAM,21,10,2200", "ORG,1,1,1100", "ORG,21,10,2200"})
    void TEAMとORGのpaid契約は現在人数に合う新Short帯を保存する(
            EntitlementScopeKind scope, int memberCount, short expectedBand, int price) {
        prepareMemberScope(scope, memberCount);
        BillingContractService.ContractResult result = contracts.createPendingPaidContract(scope, ownedScopeId,
                organizationId, ContractKind.PLAN, planKey, null, price, actor);

        transactions.executeWithoutResult(tx -> {
            BillingContractEntity saved = contractRepository.findById(result.contractId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ContractStatus.PENDING);
            assertThat(saved.getMemberCountSnapshot()).isEqualTo(memberCount);
            assertThat(saved.getBandNoSnapshot()).isEqualTo(expectedBand);
            assertThat(saved.getPriceJpySnapshot()).isEqualTo(price);
            assertThat(pointers.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                    scope, ownedScopeId, ContractKind.PLAN, "").orElseThrow().getContractId()).isEqualTo(saved.getId());
            assertThat(entitlements.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                    EntitlementSourceKind.PLAN, saved.getId())).isEmpty();
        });
        assertThat(pspCreates()).isZero();
    }

    private void prepareMemberScope(EntitlementScopeKind scope, int count) {
        ownedScope = scope;
        transactions.executeWithoutResult(tx -> {
            String slug = "i1931h-" + UUID.randomUUID().toString().substring(0, 16);
            if (scope == EntitlementScopeKind.TEAM) {
                teamId = teams.saveAndFlush(TeamEntity.builder().name("1931handoff人数")
                        .slug(slug).visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build())
                        .getId();
                ownedScopeId = teamId;
            } else {
                organizationId = organizations.saveAndFlush(OrganizationEntity.builder().name("1931handoff人数")
                        .slug(slug).orgType(OrganizationEntity.OrgType.OTHER)
                        .visibility(OrganizationEntity.Visibility.PRIVATE)
                        .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build())
                        .getId();
                ownedScopeId = organizationId;
            }
            RoleEntity admin = roles.findByName("ADMIN").orElseGet(() -> {
                RoleEntity created = roles.saveAndFlush(RoleEntity.builder().name("ADMIN").displayName("管理者")
                        .priority(com.mannschaft.app.common.visibility.RolePriority.priority("ADMIN"))
                        .isSystem(false).build());
                createdAdminRole = created.getId();
                return created;
            });
            userRoleIds.add(userRoles.saveAndFlush(UserRoleEntity.builder().userId(actor).roleId(admin.getId())
                    .teamId(teamId).organizationId(organizationId).grantedBy(actor).build()).getId());
            memberIds.add(memberships.saveAndFlush(MembershipEntity.builder().userId(actor)
                    .scopeType(scope == EntitlementScopeKind.TEAM ? ScopeType.TEAM : ScopeType.ORGANIZATION)
                    .scopeId(ownedScopeId).joinedAt(java.time.LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .build()).getId());
            legacyBands.saveAndFlush(PlanPriceBandEntity.builder().planKey(planKey)
                    .scopeKind(scope == EntitlementScopeKind.TEAM ? PlanPriceBandScopeKind.TEAM : PlanPriceBandScopeKind.ORG)
                    .bandNo((short) 1).minMembers(1).monthlyPriceJpy(A_PRICE).build());
            BillingPriceVersionEntity version = versions.saveAndFlush(BillingPriceVersionEntity.builder()
                    .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(scope)
                    .catalogRevision("1931-member-handoff").revisionNo(1L).status(BillingPriceVersionStatus.ACTIVE)
                    .effectiveFrom(BOUNDARY.minusSeconds(86_400)).provisionAttempts(0)
                    .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
            versionIds.add(version.getId());
            saveMemberBand(version, 1, 1, 20, A_PRICE, 1_000L, 100L);
            saveMemberBand(version, 10, 21, null, 2_200, 2_000L, 200L);
        });
        for (int index = 1; index < count; index++) addMember();
    }

    private void addMember() {
        transactions.executeWithoutResult(tx -> {
            Long user = users.saveAndFlush(UserEntity.builder()
                    .email("i1931-member-" + UUID.randomUUID() + "@example.com")
                    .lastName("試練").firstName("1931").displayName("1931実member")
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build()).getId();
            extraUsers.add(user);
            memberIds.add(memberships.saveAndFlush(MembershipEntity.builder().userId(user)
                    .scopeType(ownedScope == EntitlementScopeKind.TEAM ? ScopeType.TEAM : ScopeType.ORGANIZATION)
                    .scopeId(ownedScopeId).joinedAt(java.time.LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .build()).getId());
        });
    }

    private void saveMemberBand(BillingPriceVersionEntity parent, int number, int min, Integer max,
                                long total, long excluding, long tax) {
        bandIds.add(bands.saveAndFlush(BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(parent.getScopeKind())
                .priceVersionId(parent.getId()).bandNo(number).minMembers(min).maxMembers(max).currency("JPY")
                .stripePriceRef("price_it1931_member_" + UUID.randomUUID()).inputAmount(total)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(excluding).taxAmount(tax).amountIncludingTax(total).taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税").includedInPrice(true).effectiveFrom(parent.getEffectiveFrom())
                .status(BillingPriceVersionStatus.ACTIVE)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build()).getId());
    }

    @Test
    void 無料changePlanの前に有料化したら017で旧契約pointer権利を変更しない() {
        String oldPlan = "I1931O-" + UUID.randomUUID().toString().substring(0, 16);
        transactions.executeWithoutResult(tx -> {
            otherPlans.add(oldPlan);
            plans.saveAndFlush(PlanEntity.builder().planKey(oldPlan).baseMonthlyPriceJpy(0)
                    .displayNameKey("it1931").descriptionKey("it1931").sortOrder(0).enabled(true).build());
            planFeatures.saveAndFlush(PlanFeatureEntity.builder().planKey(oldPlan).featureKey(featureKey).build());
        });
        UUID oldId = contracts.createContract(EntitlementScopeKind.USER, actor, null,
                ContractKind.PLAN, oldPlan, null, actor).contractId();
        removeCurrentPrice();
        transactions.executeWithoutResult(tx -> plans.findById(planKey).orElseThrow().setBaseMonthlyPriceJpy(0));
        assertThat(resolve()).isZero();
        List<Object> before = savedContractState(oldId);
        List<UUID> rightsBefore = entitlementIds(oldId);
        UUID pointerBefore = pointers.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                EntitlementScopeKind.USER, actor, ContractKind.PLAN, "").orElseThrow().getId();
        time(BOUNDARY);

        assertError(() -> contracts.changePlan(EntitlementScopeKind.USER, actor, oldId, planKey, actor),
                EntitlementErrorCode.CONTRACT_CHANGE_REQUIRES_PAYMENT);

        assertThat(savedContractState(oldId)).isEqualTo(before);
        assertThat(entitlementIds(oldId)).isEqualTo(rightsBefore).hasSize(1);
        assertThat(ownedContracts()).hasSize(1);
        ActiveContractPointerEntity pointer = pointers.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                EntitlementScopeKind.USER, actor, ContractKind.PLAN, "").orElseThrow();
        assertThat(pointer.getId()).isEqualTo(pointerBefore);
        assertThat(pointer.getContractId()).isEqualTo(oldId);
        assertThat(pspCreates()).isZero();
    }

    /** AC15の既存履歴characterization。新販売Bとは独立に保存A/FK/invoiceを保つ。 */
    @Test
    void 販売B読取後も保存AのFK額と退役band参照と過去invoiceを変更しない() {
        UUID savedBand = bandIds.getFirst();
        UUID contractId = contracts.createPendingPaidContract(EntitlementScopeKind.USER, actor, null,
                ContractKind.PLAN, planKey, null, A_PRICE, actor).contractId();
        transactions.executeWithoutResult(tx -> {
            BillingContractEntity contract = contractRepository.findById(contractId).orElseThrow();
            contract.setStatus(ContractStatus.ACTIVE);
            contract.setPriceBandVersionId(savedBand);
            customerId = customers.saveAndFlush(BillingCustomerEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER).scopeId(actor).pspCustomerRef("cus_it1931_" + actor)
                    .status("ACTIVE").provisionAttempts(0).version(0L)
                    .createdAt(BOUNDARY.minusSeconds(100)).updatedAt(BOUNDARY.minusSeconds(100)).build()).getId();
            invoiceId = invoices.saveAndFlush(BillingInvoiceEntity.builder().billingCustomerId(customerId)
                    .contractId(contractId).scopeKind(EntitlementScopeKind.USER).scopeId(actor)
                    .pspInvoiceRef("in_it1931_" + UUID.randomUUID()).billingReason("subscription_cycle")
                    .status("PAID").periodStart(BOUNDARY.minusSeconds(86_400)).periodEnd(BOUNDARY)
                    .currency("JPY").subtotalAmount(1_000L).discountAmount(0L).taxAmount(100L)
                    .totalAmount((long) A_PRICE).issuerNameSnapshot("1931試練")
                    .paidAt(BOUNDARY.minusSeconds(1)).version(0L)
                    .createdAt(BOUNDARY.minusSeconds(100)).updatedAt(BOUNDARY.minusSeconds(1)).build()).getId();
        });
        List<Object> contractBefore = savedContractState(contractId);
        List<Object> invoiceBefore = savedInvoiceState();
        time(BOUNDARY);
        assertThat(selector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)
                .orElseThrow().bands().getFirst().getAmountIncludingTax()).isEqualTo((long) B_PRICE);

        catalog.getCatalog();
        resolve();
        transactions.executeWithoutResult(tx -> {
            BillingContractEntity saved = contractRepository.findById(contractId).orElseThrow();
            BillingPriceBandVersionEntity historical = currentBands.resolveContractBand(saved, 1, clock.instant())
                    .orElseThrow();
            assertThat(historical.getId()).isEqualTo(savedBand);
            assertThat(historical.getStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED);
            assertThat(historical.getAmountIncludingTax()).isEqualTo((long) A_PRICE);
        });

        assertThat(savedContractState(contractId)).isEqualTo(contractBefore);
        assertThat(savedInvoiceState()).isEqualTo(invoiceBefore);
        assertThat(pspCreates()).isZero();
    }

    private List<Object> savedContractState(UUID id) {
        return transactions.execute(tx -> {
            BillingContractEntity saved = contractRepository.findById(id).orElseThrow();
            return new ArrayList<>(java.util.Arrays.asList(saved.getId(), saved.getScopeKind(), saved.getScopeId(),
                    saved.getPlanKey(), saved.getStatus(), saved.getPriceBandVersionId(), saved.getPriceJpySnapshot(),
                    saved.getBandNoSnapshot(), saved.getMemberCountSnapshot(), saved.getContractedAt(),
                    saved.getCancelledAt(), saved.getVersion(), saved.getCreatedAt(), saved.getUpdatedAt()));
        });
    }

    private List<UUID> entitlementIds(UUID contractId) {
        return transactions.execute(tx -> entitlements.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                EntitlementSourceKind.PLAN, contractId).stream().map(EntitlementEntity::getId).sorted().toList());
    }

    private List<Object> savedInvoiceState() {
        return transactions.execute(tx -> {
            BillingInvoiceEntity saved = invoices.findById(invoiceId).orElseThrow();
            return new ArrayList<>(java.util.Arrays.asList(saved.getId(), saved.getBillingCustomerId(),
                    saved.getContractId(), saved.getOrganizationId(), saved.getScopeKind(), saved.getScopeId(),
                    saved.getPspInvoiceRef(), saved.getPspSubscriptionRef(), saved.getBillingReason(), saved.getStatus(),
                    saved.getPeriodStart(), saved.getPeriodEnd(), saved.getCurrency(), saved.getSubtotalAmount(),
                    saved.getDiscountAmount(), saved.getTaxAmount(), saved.getTotalAmount(), saved.getIssuerNameSnapshot(),
                    saved.getBillingNameSnapshot(), saved.getBillingEmailSnapshot(), saved.getBillingAddressSnapshot(),
                    saved.getFinalizedAt(), saved.getPaidAt(), saved.getVoidedAt(), saved.getVersion(),
                    saved.getCreatedAt(), saved.getUpdatedAt(), saved.getDeletedAt()));
        });
    }

    private Integer resolve() {
        return resolver.resolveMonthlyPriceJpy(EntitlementScopeKind.USER, actor, ContractKind.PLAN, planKey, null);
    }

    private void assertError(Runnable action, EntitlementErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(expected);
    }

    private void assertNoContractSideEffects() {
        transactions.executeWithoutResult(tx -> {
            assertThat(ownedContracts()).isEmpty();
            assertThat(pointers.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                    ownedScope, ownedScopeId, ContractKind.PLAN, "")).isEmpty();
            assertThat(entitlements.findActiveByScope(ownedScope, ownedScopeId,
                    java.time.LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))).isEmpty();
        });
        assertThat(pspCreates()).isZero();
    }

    private List<BillingContractEntity> ownedContracts() {
        return contractRepository.findByScopeKindAndScopeIdAndDeletedAtIsNullOrderByContractedAtDesc(
                ownedScope, ownedScopeId);
    }

    private long pspCreates() {
        return mockingDetails(gateway).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("createSubscriptionCheckout")).count();
    }

    private void removeCurrentPrice() {
        transactions.executeWithoutResult(tx -> {
            bands.deleteById(bandIds.getFirst());
            bands.flush();
            versions.deleteById(versionIds.getFirst());
            versions.flush();
        });
        bandIds.removeFirst();
        versionIds.removeFirst();
    }

    private void savePrice(long revision, BillingPriceVersionStatus status, Instant from, Instant until,
                           long total, long excluding, long tax) {
        BillingPriceVersionEntity version = versions.saveAndFlush(BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(EntitlementScopeKind.USER)
                .catalogRevision("1931-handoff-" + revision).revisionNo(revision).status(status)
                .effectiveFrom(from).effectiveUntil(until).provisionAttempts(0)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
        versionIds.add(version.getId());
        bandIds.add(bands.saveAndFlush(BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(EntitlementScopeKind.USER)
                .priceVersionId(version.getId()).bandNo(1).minMembers(1).currency("JPY")
                .stripePriceRef("price_it1931_handoff_" + UUID.randomUUID()).inputAmount(total)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(excluding).taxAmount(tax).amountIncludingTax(total).taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税").includedInPrice(true).effectiveFrom(from).effectiveUntil(until)
                .status(status).creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build()).getId());
    }

    private void time(Instant at) {
        ((HandoffClock) clock).at.set(at);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HandoffConfiguration {
        @Bean ResolverReturnHook resolverReturnHook() { return new ResolverReturnHook(); }
    }

    /** 実戻り値をそのまま返す、現スレッド一回限りの同期hook。 */
    @Aspect
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    static class ResolverReturnHook {
        private static final ThreadLocal<java.util.function.Consumer<Object>> CURRENT = new ThreadLocal<>();

        @Around("execution(* com.mannschaft.app.billing.BillingPriceResolver.resolveMonthlyPriceJpy(..))")
        Object afterActualReturn(ProceedingJoinPoint invocation) throws Throwable {
            Object value = invocation.proceed();
            var hook = CURRENT.get();
            if (hook != null) {
                CURRENT.remove();
                hook.accept(value);
            }
            return value;
        }
    }

    /** 時点だけを進める実Clock。zone派生も同じ時点参照を共有する。 */
    private static final class HandoffClock extends Clock {
        private final AtomicReference<Instant> at;
        private final ZoneId zone;
        private HandoffClock(Instant instant, ZoneId zone) { this(new AtomicReference<>(instant), zone); }
        private HandoffClock(AtomicReference<Instant> at, ZoneId zone) { this.at = at; this.zone = zone; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId newZone) { return new HandoffClock(at, newZone); }
        @Override public Instant instant() { return at.get(); }
    }
}
