package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.billing.api.BillingCatalogQueryService;
import com.mannschaft.app.billing.api.dto.PlanItem;
import com.mannschaft.app.billing.api.dto.PriceBand;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * CMP-260930-1931 AC01/03/04/06: 新ACTIVE価格と人数境界をlegacy入口から読む先行試練。
 * 実Beanと実MySQLを使い、fixtureをcommitしてから別の読取トランザクションへ渡す。
 * HTTP認可・契約保存の証拠は別試練が担い、この試験で代用しない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(BillingLegacyPlanPriceReadIT.SnapshotObservationConfiguration.class)
@DisplayName("1931 legacy PLAN価格の正本読取")
class BillingLegacyPlanPriceReadIT extends AbstractMySqlIntegrationTest {

    private static final int LEGACY_PRICE = 1_100;
    private static final long ACTIVE_PRICE = 3_630L;
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

    @Autowired private PlanRepository planRepository;
    @Autowired private PlanPriceBandRepository legacyBandRepository;
    @Autowired private BillingPriceVersionRepository versionRepository;
    @Autowired private BillingPriceBandVersionRepository bandRepository;
    @Autowired private BillingCatalogQueryService catalogService;
    @Autowired private BillingPriceResolver priceResolver;
    @Autowired private BillingPriceSelector priceSelector;
    @Autowired private LegacyPlanPriceSnapshotReader snapshotReader;
    @Autowired private ScopeMemberCountService memberCountService;
    @Autowired private UserRepository userRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private FeatureCatalogRepository featureRepository;
    @Autowired private TransactionTemplate transactions;
    @Autowired private BillingContractService contractService;
    @Autowired private BillingContractRepository contractRepository;
    @Autowired private ActiveContractPointerRepository pointerRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @TestBean(name = "utcClock", methodName = "priceReadClock") private Clock clock;

    private String planKey;
    private final List<UUID> versionIds = new ArrayList<>();
    private final List<UUID> bandIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> membershipIds = new ArrayList<>();
    private final List<String> featureKeys = new ArrayList<>();
    private final List<String> additionalPlans = new ArrayList<>();
    private final List<UUID> contractIds = new ArrayList<>();
    private Long teamId;
    private Long organizationId;

    static Clock priceReadClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @BeforeEach
    void 検証専用の旧価格と異なるACTIVE価格を保存する() {
        planKey = "I1931-" + UUID.randomUUID().toString().substring(0, 16);
        transactions.executeWithoutResult(tx -> {
            planRepository.saveAndFlush(PlanEntity.builder().planKey(planKey)
                    .displayNameKey("billing.it1931.name").descriptionKey("billing.it1931.description")
                    .baseMonthlyPriceJpy(LEGACY_PRICE).sortOrder(0).enabled(true).build());
            for (EntitlementScopeKind scope : EntitlementScopeKind.values()) {
                if (scope != EntitlementScopeKind.USER) {
                    legacyBandRepository.saveAndFlush(PlanPriceBandEntity.builder()
                            .planKey(planKey).scopeKind(scope == EntitlementScopeKind.TEAM
                                    ? PlanPriceBandScopeKind.TEAM : PlanPriceBandScopeKind.ORG)
                            .bandNo((short) 1).minMembers(1).monthlyPriceJpy(LEGACY_PRICE).build());
                }
                BillingPriceVersionEntity version = versionRepository.saveAndFlush(
                        BillingPriceVersionEntity.builder().productKind(BillingProductKind.PLAN)
                                .productKey(planKey).scopeKind(scope).catalogRevision("1931-active")
                                .revisionNo(1L).status(BillingPriceVersionStatus.ACTIVE)
                                .effectiveFrom(EFFECTIVE_FROM).provisionAttempts(0)
                                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
                versionIds.add(version.getId());
                BillingPriceBandVersionEntity band = bandRepository.saveAndFlush(
                        BillingPriceBandVersionEntity.builder().productKind(BillingProductKind.PLAN)
                                .productKey(planKey).scopeKind(scope).priceVersionId(version.getId())
                                .bandNo(1).minMembers(1).currency("JPY")
                                .stripePriceRef("price_it1931_" + UUID.randomUUID())
                                .inputAmount(ACTIVE_PRICE).taxBehavior(BillingTaxBehavior.INCLUSIVE)
                                .taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                                .amountExcludingTax(3_300L).taxAmount(330L)
                                .amountIncludingTax(ACTIVE_PRICE).taxRateBasisPoints(1_000)
                                .taxNameSnapshot("消費税").includedInPrice(true)
                                .effectiveFrom(EFFECTIVE_FROM).status(BillingPriceVersionStatus.ACTIVE)
                                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
                bandIds.add(band.getId());
            }
        });
    }

    @AfterEach
    void 所有fixtureだけを削除する() {
        SnapshotInterleaveHook.CURRENT.remove();
        transactions.executeWithoutResult(tx -> {
            for (UUID contractId : contractIds) {
                BillingContractEntity contract = contractRepository.findById(contractId).orElseThrow();
                entitlementRepository.deleteAll(entitlementRepository
                        .findBySourceKindAndSourceRefIdAndRevokedAtIsNull(EntitlementSourceKind.PLAN, contractId));
                entitlementRepository.flush();
                pointerRepository.hardDeleteBySlotAndContractId(contract.getScopeKind(), contract.getScopeId(),
                        ContractKind.PLAN, "", contractId);
                contractRepository.delete(contract);
                contractRepository.flush();
            }
            membershipRepository.deleteAllById(membershipIds);
            membershipRepository.flush();
            userRepository.deleteAllById(userIds);
            if (teamId != null) {
                teamRepository.deleteById(teamId);
            }
            if (organizationId != null) {
                organizationRepository.deleteById(organizationId);
            }
            featureRepository.deleteAllById(featureKeys);
            bandRepository.deleteAllById(bandIds);
            bandRepository.flush();
            versionRepository.deleteAllById(versionIds);
            versionRepository.flush();
            if (planKey != null) {
                legacyBandRepository.deleteAll(legacyBandRepository
                        .findByPlanKeyAndScopeKindOrderByBandNoAsc(planKey, PlanPriceBandScopeKind.TEAM));
                legacyBandRepository.deleteAll(legacyBandRepository
                        .findByPlanKeyAndScopeKindOrderByBandNoAsc(planKey, PlanPriceBandScopeKind.ORG));
                legacyBandRepository.flush();
                planRepository.deleteById(planKey);
                planRepository.deleteAllById(additionalPlans);
            }
        });
    }

    @Test
    void 会員カタログ_USER月額は旧baseでなく新ACTIVE税込額である() {
        PlanItem result = catalogService.getCatalog().getPlans().stream()
                .filter(plan -> planKey.equals(plan.getPlanKey())).findFirst().orElseThrow();

        assertThat(result.getBaseMonthlyPriceJpy()).isEqualTo((int) ACTIVE_PRICE);
    }

    @Test
    void 会員カタログ_TEAMとORG帯は旧表でなく新ACTIVE税込額である() {
        PlanItem result = catalogService.getCatalog().getPlans().stream()
                .filter(plan -> planKey.equals(plan.getPlanKey())).findFirst().orElseThrow();

        assertThat(result.getPriceBands())
                .extracting(PriceBand::getScopeKind, PriceBand::getBandNo, PriceBand::getMonthlyPriceJpy)
                .containsExactly(tuple("TEAM", 1, (int) ACTIVE_PRICE), tuple("ORG", 1, (int) ACTIVE_PRICE));
    }

    @Test
    void 契約価格解決_USER月額は旧baseでなく新ACTIVE税込額である() {
        Integer result = priceResolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 1L, ContractKind.PLAN, planKey, null);

        assertThat(result).isEqualTo((int) ACTIVE_PRICE);
    }

    /** AC01陽性対照。fixture自体が既存modern選択器の適格判定を満たすことを実証する。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(EntitlementScopeKind.class)
    void 同じfixtureは既存modern選択器でも新ACTIVE税込3630として適格である(EntitlementScopeKind scope) {
        BillingPriceSelector.SelectedPrice selected = priceSelector
                .selectNow(BillingProductKind.PLAN, planKey, scope).orElseThrow();

        assertThat(selected.revision().getScopeKind()).isEqualTo(scope);
        assertThat(selected.revision().getProductKey()).isEqualTo(planKey);
        assertThat(selected.bands()).hasSize(1).allSatisfy(band -> {
            assertThat(band.getScopeKind()).isEqualTo(scope);
            assertThat(band.getPriceVersionId()).isEqualTo(selected.revision().getId());
            assertThat(band.getAmountIncludingTax()).isEqualTo(ACTIVE_PRICE);
        });
    }

    /** AC01/06。実在team/orgと実membershipのcommit後に人数を数え直す。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"TEAM,1,3630", "TEAM,19,3630", "TEAM,20,3630", "TEAM,21,4840",
            "TEAM,49,4840", "TEAM,50,4840", "TEAM,51,6050",
            "ORG,1,3630", "ORG,19,3630", "ORG,20,3630", "ORG,21,4840",
            "ORG,49,4840", "ORG,50,4840", "ORG,51,6050"})
    void TEAMとORGは実在籍人数の境界に合う新ACTIVE帯の価格を解決する(
            EntitlementScopeKind scope, int members, int expected) {
        Long scopeId = prepareMembers(scope, members);
        splitActiveMemberBands(scope, false);
        assertThat(memberCountService.countActiveMembers(scope, scopeId)).isEqualTo(members);

        assertThat(priceResolver.resolveMonthlyPriceJpy(scope, scopeId, ContractKind.PLAN, planKey, null))
                .isEqualTo(expected);
    }

    /** AC03/06。人数0と保存済帯のgapを旧baseへfallbackさせない。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"TEAM,0,false", "ORG,0,false", "TEAM,21,true", "ORG,21,true"})
    void 対象人数を覆わない場合は旧baseでなく未設定040で拒否する(
            EntitlementScopeKind scope, int members, boolean gap) {
        Long scopeId = prepareMembers(scope, members);
        splitActiveMemberBands(scope, gap);

        assertNotConfigured(scope, scopeId);
    }

    /** AC06。保存済gapは帯一覧全体を不適格にしない（create APIの連続条件とは別）。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(value = EntitlementScopeKind.class, names = {"TEAM", "ORG"})
    void 保存済の人数gapがあってもそのscopeの適格な帯一覧は全て表示する(EntitlementScopeKind scope) {
        splitActiveMemberBands(scope, true);
        assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, scope)
                .orElseThrow().bands()).hasSize(3);

        assertThat(ownedCatalog().getPriceBands()).filteredOn(band -> scope.name().equals(band.getScopeKind()))
                .extracting(PriceBand::getBandNo, PriceBand::getMinMembers, PriceBand::getMaxMembers,
                        PriceBand::getMonthlyPriceJpy)
                .containsExactly(tuple(1, 1, 20, 3630), tuple(2, 22, 50, 4840), tuple(10, 51, null, 6050));
    }

    /** AC03。旧価格が存在してもACTIVE不在から有償価格を推測しない。 */
    @Test
    void ACTIVE不在のcatalogは旧価格でなくUSER未定と空帯を返す() {
        removeActivePrices();

        assertThat(ownedCatalog().getBaseMonthlyPriceJpy()).isNull();
        assertThat(ownedCatalog().getPriceBands()).isEmpty();
    }

    /** AC03。PLAN不在nullとは異なり、存在するPLANの価格欠損は040。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(EntitlementScopeKind.class)
    void ACTIVE不在の存在PLANは契約価格未設定040で拒否する(EntitlementScopeKind scope) {
        removeActivePrices();

        assertNotConfigured(scope, scope == EntitlementScopeKind.USER ? 1L : prepareMembers(scope, 1));
    }

    /** AC03/05。NOT NULLを破らず保存可能な不適格値を既存scalarと同じく拒否する。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"emptyTaxName", "inconsistentTotal", "retiredBand", "emptyBands",
            "emptyTaxCode", "blankStripe", "overlappingBands"})
    void 不適格な新価格は旧baseへfallbackせず未設定040で拒否する(String defect) {
        makeActiveInvalid(defect);
        assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)).isEmpty();

        assertNotConfigured(EntitlementScopeKind.USER, 1L);
    }

    /** AC04。旧base=0が新ACTIVEの有償価格を上書きしてはならない。 */
    @Test
    void 旧baseが明示0でも有償ACTIVEがあればその3630を解決する() {
        transactions.executeWithoutResult(tx -> planRepository.findById(planKey)
                .orElseThrow().setBaseMonthlyPriceJpy(0));

        assertThat(priceResolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 1L, ContractKind.PLAN, planKey, null)).isEqualTo((int) ACTIVE_PRICE);
    }

    /** AC04の既存互換characterization。初回greenが正常。 */
    @Test
    void マスタ不在PLANのnullは既存の呼出側NOT_FOUND契約として維持する() {
        assertThat(priceResolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 1L, ContractKind.PLAN, planKey + "-missing", null)).isNull();
    }

    /** AC04の既存ADDON互換characterization。価格正本をPLANへ巻き込まない。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"NULL,", "ZERO,0", "PAID,300"})
    void ADDONのnullと0と有料は既存互換を維持する(String suffix, Integer expected) {
        String featureKey = planKey.toLowerCase(java.util.Locale.ROOT) + "." + suffix.toLowerCase(java.util.Locale.ROOT);
        transactions.executeWithoutResult(tx -> {
            featureRepository.saveAndFlush(FeatureCatalogEntity.builder().featureKey(featureKey)
                    .category(FeatureCategory.INTERNAL).addonAvailable(true).addonPriceJpy(expected)
                    .freeForNonprofit(false).displayNameKey("it1931").descriptionKey("it1931")
                    .sortOrder(0).enabled(true).build());
            featureKeys.add(featureKey);
        });

        assertThat(priceResolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 1L, ContractKind.ADDON, null, featureKey)).isEqualTo(expected);
    }

    /** AC04既存互換。新公開設定が一度もない明示0だけは無料を維持する。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(EntitlementScopeKind.class)
    void 新価格モデルなしの明示0は実人数の各scopeで無料を維持する(EntitlementScopeKind scope) {
        removeActivePrices();
        setLegacyFree();
        Long scopeId = scope == EntitlementScopeKind.USER ? 1L : prepareMembers(scope, 1);

        assertThat(priceResolver.resolveMonthlyPriceJpy(scope, scopeId, ContractKind.PLAN, planKey, null)).isZero();
    }

    /** READY以前・取消済み・未来予約だけから公開履歴を推定しない。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(value = BillingPriceVersionStatus.class,
            names = {"DRAFT", "PROVISIONING", "PROVISION_FAILED", "READY", "SCHEDULED", "CANCELLED"})
    void 未公開状態と未来予約だけなら旧明示0を無料として扱う(BillingPriceVersionStatus status) {
        setLegacyFree();
        replacePriceLifecycle(status, NOW.plusSeconds(60), null);

        assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)).isEmpty();
        assertThat(priceResolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 1L, ContractKind.PLAN, planKey, null)).isZero();
    }

    /** AC03/04: 公開済みだが販売不能なモデルを旧0円へ戻すと無料権利を誤発行する。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"emptyTaxName", "inconsistentTotal", "retiredBand", "emptyBands",
            "emptyTaxCode", "blankStripe", "overlappingBands"})
    void 旧明示0でも新ACTIVEの不適格を無料へfallbackしない(String defect) {
        setLegacyFree();
        makeActiveInvalid(defect);
        assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)).isEmpty();

        assertNotConfigured(EntitlementScopeKind.USER, 1L);
    }

    /** 期限切れACTIVE・RETIRED履歴・開始済み予約は無料への巻き戻しを禁止する。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(value = BillingPriceVersionStatus.class, names = {"ACTIVE", "RETIRED", "SCHEDULED"})
    void 公開履歴又は開始済み予約が期限切れなら旧明示0でも040にする(BillingPriceVersionStatus status) {
        setLegacyFree();
        replacePriceLifecycle(status, NOW.minusSeconds(60), NOW.minusSeconds(1));
        assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)).isEmpty();

        assertNotConfigured(EntitlementScopeKind.USER, 1L);
    }

    @Test
    void 開始済み予約の販売不適格でも旧明示0へ戻さない() {
        setLegacyFree();
        replacePriceLifecycle(BillingPriceVersionStatus.SCHEDULED, NOW.minusSeconds(1), null);
        transactions.executeWithoutResult(tx -> bandRepository.findById(bandIds.getFirst()).orElseThrow()
                .setTaxNameSnapshot(""));
        assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)).isEmpty();

        assertNotConfigured(EntitlementScopeKind.USER, 1L);
    }

    /** 初回greenが正常の既存互換。FREEが不在なら所有fixtureを作り、既存行は再利用する。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void FREEと新公開価格なしの明示0は実無料契約を作成できる(boolean existingFree) {
        removeActivePrices();
        setLegacyFree();
        String targetPlan = existingFree ? "FREE" : planKey;
        if (existingFree) {
            transactions.executeWithoutResult(tx -> {
                if (planRepository.findById("FREE").isEmpty()) {
                    planRepository.saveAndFlush(PlanEntity.builder().planKey("FREE").baseMonthlyPriceJpy(0)
                            .displayNameKey("billing.plans.free.name").descriptionKey("billing.plans.free.description")
                            .sortOrder(0).enabled(true).build());
                    additionalPlans.add("FREE");
                }
            });
        }
        Long actor = transactions.execute(tx -> {
            Long id = userRepository.saveAndFlush(UserEntity.builder()
                    .email("i1931-free-" + UUID.randomUUID() + "@example.com")
                    .lastName("試練").firstName("1931").displayName("1931無料契約")
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build()).getId();
            userIds.add(id);
            return id;
        });
        assertThat(planRepository.findById(targetPlan).orElseThrow().getBaseMonthlyPriceJpy()).isZero();
        assertThat(priceResolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, actor, ContractKind.PLAN, targetPlan, null)).isZero();

        BillingContractService.ContractResult result = contractService.createContract(
                EntitlementScopeKind.USER, actor, null, ContractKind.PLAN, targetPlan, null, actor);
        contractIds.add(result.contractId());

        transactions.executeWithoutResult(tx -> {
            BillingContractEntity saved = contractRepository.findById(result.contractId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(ContractStatus.ACTIVE);
            assertThat(saved.getPlanKey()).isEqualTo(targetPlan);
            assertThat(saved.getPriceJpySnapshot()).isNull();
            assertThat(saved.getMemberCountSnapshot()).isNull();
            assertThat(saved.getBandNoSnapshot()).isNull();
            assertThat(pointerRepository.findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                    EntitlementScopeKind.USER, actor, ContractKind.PLAN, "").orElseThrow().getContractId())
                    .isEqualTo(saved.getId());
            assertThat(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                    EntitlementSourceKind.PLAN, saved.getId()))
                    .extracting(EntitlementEntity::getFeatureKey).containsExactlyInAnyOrderElementsOf(result.grantedFeatureKeys());
        });
    }

    private void setLegacyFree() {
        transactions.executeWithoutResult(tx -> {
            planRepository.findById(planKey).orElseThrow().setBaseMonthlyPriceJpy(0);
            for (PlanPriceBandScopeKind scope : PlanPriceBandScopeKind.values()) {
                legacyBandRepository.findByPlanKeyAndScopeKindOrderByBandNoAsc(planKey, scope)
                        .forEach(band -> band.setMonthlyPriceJpy(0));
            }
        });
    }

    private void replacePriceLifecycle(BillingPriceVersionStatus status, Instant from, Instant until) {
        transactions.executeWithoutResult(tx -> {
            for (UUID id : versionIds) {
                BillingPriceVersionEntity version = versionRepository.findById(id).orElseThrow();
                version.setStatus(status);
                version.setEffectiveFrom(from);
                version.setEffectiveUntil(until);
            }
            for (UUID id : bandIds) {
                BillingPriceBandVersionEntity band = bandRepository.findById(id).orElseThrow();
                band.setStatus(status);
                band.setEffectiveFrom(from);
                band.setEffectiveUntil(until);
                if (status == BillingPriceVersionStatus.DRAFT || status == BillingPriceVersionStatus.PROVISIONING
                        || status == BillingPriceVersionStatus.PROVISION_FAILED || status == BillingPriceVersionStatus.CANCELLED) {
                    band.setStripePriceRef(null);
                }
            }
        });
    }

    /** batch骨格の未実装UOEは既存public入口の業務redから別分類する。 */
    @Test
    void batchは要求全scopeの検証済scalarを返しTX外読取でSQLを増やさない() {
        Set<BillingPriceSelector.PlanScopeKey> keys = ownedPriceKeys();
        Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> selected =
                priceSelector.selectLegacyPlans(keys);
        assertThat(selected.keySet()).containsExactlyInAnyOrderElementsOf(keys);
        for (BillingPriceSelector.PlanScopeKey key : keys) {
            BillingPriceSelector.LegacyPlanSelection selection = selected.get(key);
            assertThat(selection.state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.SELECTED);
            assertThat(selection.snapshot()).isNotNull();
            BillingPriceVersionEntity parent = versionRepository.findAllById(versionIds).stream()
                    .filter(version -> version.getScopeKind() == key.scopeKind()).findFirst().orElseThrow();
            BillingPriceBandVersionEntity band = bandRepository.findAllById(bandIds).stream()
                    .filter(item -> item.getScopeKind() == key.scopeKind()).findFirst().orElseThrow();
            assertThat(selection.snapshot().priceVersionId()).isEqualTo(parent.getId());
            assertThat(selection.snapshot().bands()).containsExactly(new BillingPriceSelector.LegacyPlanBandSnapshot(
                    band.getId(), 1, 1, null, ACTIVE_PRICE));
        }
        try (var capture = com.mannschaft.app.common.visibility.perf.SqlIntentCounter.captureCurrentThread()) {
            selected.values().forEach(selection -> {
                assertThat(selection.snapshot().priceVersionId()).isNotNull();
                assertThat(selection.snapshot().bands()).allSatisfy(band -> {
                    assertThat(band.id()).isNotNull();
                    assertThat(band.amountIncludingTax()).isEqualTo(ACTIVE_PRICE);
                });
            });
            assertThat(capture.capturedSqls()).isEmpty();
        }
    }

    @Test
    void batchは新公開設定なしをkey不在でなく明示状態で返す() {
        removeActivePrices();

        assertBatchState(BillingPriceSelector.LegacyPlanSelectionState.NO_PUBLISHED_PRICE);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(value = BillingPriceVersionStatus.class,
            names = {"DRAFT", "PROVISIONING", "PROVISION_FAILED", "READY", "SCHEDULED", "CANCELLED"})
    void batchは未公開と未来予約だけなら新公開設定なしを返す(BillingPriceVersionStatus status) {
        replacePriceLifecycle(status, NOW.plusSeconds(60), null);

        assertBatchState(BillingPriceSelector.LegacyPlanSelectionState.NO_PUBLISHED_PRICE);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @EnumSource(value = BillingPriceVersionStatus.class, names = {"ACTIVE", "RETIRED", "SCHEDULED"})
    void batchは期限切れの公開又は開始済予約を新公開設定なしと混同しない(BillingPriceVersionStatus status) {
        replacePriceLifecycle(status, NOW.minusSeconds(60), NOW.minusSeconds(1));

        assertBatchState(BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE);
    }

    @Test
    void batchの不適格ACTIVEは新公開設定なしではなく販売不能を返す() {
        transactions.executeWithoutResult(tx -> bandRepository.findAllById(bandIds)
                .forEach(band -> band.setTaxNameSnapshot("")));
        for (EntitlementScopeKind scope : EntitlementScopeKind.values()) {
            assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, scope)).isEmpty();
        }

        assertBatchState(BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE);
    }

    @Test
    void batchのdue昇格不能は新公開設定なしではなく販売不能を返す() {
        replacePriceLifecycle(BillingPriceVersionStatus.SCHEDULED, NOW.minusSeconds(1), null);
        transactions.executeWithoutResult(tx -> bandRepository.findAllById(bandIds)
                .forEach(band -> band.setStatus(BillingPriceVersionStatus.RETIRED)));
        for (EntitlementScopeKind scope : EntitlementScopeKind.values()) {
            assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, scope)).isEmpty();
        }

        assertBatchState(BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE);
    }

    @Test
    void batchの空要求だけは空Mapを返す() {
        assertThat(priceSelector.selectLegacyPlans(Set.of())).isEmpty();
    }

    private Set<BillingPriceSelector.PlanScopeKey> ownedPriceKeys() {
        return java.util.Arrays.stream(EntitlementScopeKind.values())
                .map(scope -> new BillingPriceSelector.PlanScopeKey(planKey, scope)).collect(Collectors.toSet());
    }

    private void assertBatchState(BillingPriceSelector.LegacyPlanSelectionState state) {
        Set<BillingPriceSelector.PlanScopeKey> keys = ownedPriceKeys();
        Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> result =
                priceSelector.selectLegacyPlans(keys);
        assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(keys);
        assertThat(result.values()).allSatisfy(selection -> {
            assertThat(selection.state()).isEqualTo(state);
            assertThat(selection.snapshot()).isNull();
        });
    }

    /** AC19正本: 直接batch区間の全SELECTをtable名で除外せず計数する。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"1,1", "1,10", "10,1", "10,10"})
    void dueなしbatchの全SELECTはplanと帯を増やしても3本以内(int planCount, int bandsPerScope) {
        Set<String> plans = prepareCatalogScale(planCount, bandsPerScope);
        Set<BillingPriceSelector.PlanScopeKey> requested = allScopeKeys(plans);
        try (var capture = com.mannschaft.app.common.visibility.perf.SqlIntentCounter.captureCurrentThread()) {
            Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> result =
                    priceSelector.selectLegacyPlans(requested);
            assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(requested);
            result.forEach((key, selection) -> {
                assertThat(selection.state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.SELECTED);
                assertThat(selection.snapshot().bands()).hasSize(
                        key.scopeKind() == EntitlementScopeKind.USER ? 1 : bandsPerScope)
                        .allSatisfy(band -> assertThat(band.amountIncludingTax()).isEqualTo(ACTIVE_PRICE));
            });
            assertThat(capture.capturedSqls()).allSatisfy(sql ->
                    assertThat(sql.toLowerCase(java.util.Locale.ROOT).stripLeading()).startsWith("select"));
            assertThat(allSelectCount(capture.capturedSqls())).isBetween(1L, 3L);
            assertThat(capture.capturedSqls()).anySatisfy(sql -> assertThat(sql).contains("billing_price_versions"));
            assertThat(capture.capturedSqls()).anySatisfy(sql -> assertThat(sql).contains("billing_price_band_versions"));
        }
    }

    private Set<BillingPriceSelector.PlanScopeKey> allScopeKeys(Set<String> planKeys) {
        return planKeys.stream().flatMap(key -> java.util.Arrays.stream(EntitlementScopeKind.values())
                .map(scope -> new BillingPriceSelector.PlanScopeKey(key, scope))).collect(Collectors.toSet());
    }

    private long allSelectCount(List<String> sqls) {
        return sqls.stream().map(sql -> sql.toLowerCase(java.util.Locale.ROOT).stripLeading())
                .filter(sql -> sql.startsWith("select")).count();
    }

    private Set<String> prepareCatalogScale(int count, int size) {
        Set<String> requested = new java.util.HashSet<>();
        requested.add(planKey);
        transactions.executeWithoutResult(tx -> {
            for (int index = 1; index < count; index++) {
                String key = "I1931S-" + UUID.randomUUID().toString().substring(0, 16);
                additionalPlans.add(key);
                requested.add(key);
                planRepository.saveAndFlush(PlanEntity.builder().planKey(key).baseMonthlyPriceJpy(LEGACY_PRICE)
                        .displayNameKey("it1931").descriptionKey("it1931").sortOrder(index).enabled(true).build());
                for (EntitlementScopeKind scope : EntitlementScopeKind.values()) {
                    BillingPriceVersionEntity version = versionRepository.saveAndFlush(BillingPriceVersionEntity.builder()
                            .productKind(BillingProductKind.PLAN).productKey(key).scopeKind(scope)
                            .catalogRevision("1931-scale").revisionNo(1L).status(BillingPriceVersionStatus.ACTIVE)
                            .effectiveFrom(EFFECTIVE_FROM).provisionAttempts(0)
                            .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
                    versionIds.add(version.getId());
                    saveScaleBand(version, 1, 1, null);
                }
            }
            if (size > 1) {
                for (UUID id : new ArrayList<>(versionIds)) {
                    BillingPriceVersionEntity version = versionRepository.findById(id).orElseThrow();
                    if (version.getScopeKind() == EntitlementScopeKind.USER) continue;
                    List<BillingPriceBandVersionEntity> existing = bandRepository
                            .findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(id);
                    existing.getFirst().setMaxMembers(1);
                    for (int no = 2; no <= size; no++) {
                        saveScaleBand(version, no, no, no == size ? null : no);
                    }
                }
            }
        });
        return requested;
    }

    private void saveScaleBand(BillingPriceVersionEntity version, int number, int min, Integer max) {
        bandIds.add(bandRepository.saveAndFlush(BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(version.getProductKey()).scopeKind(version.getScopeKind())
                .priceVersionId(version.getId()).bandNo(number).minMembers(min).maxMembers(max).currency("JPY")
                .stripePriceRef("price_it1931_scale_" + UUID.randomUUID()).inputAmount(ACTIVE_PRICE)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(3_300L).taxAmount(330L).amountIncludingTax(ACTIVE_PRICE).taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税").includedInPrice(true).effectiveFrom(EFFECTIVE_FROM)
                .status(BillingPriceVersionStatus.ACTIVE)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build()).getId());
    }

    /** AC20: 全SQL窓を維持し、due2keyの既存writer読取4Dだけを加算する。 */
    @Test
    void due2keyのbatch全SELECTは3加算4D以内で非due親子を更新しない() {
        Set<BillingPriceSelector.PlanScopeKey> requested = allScopeKeys(prepareCatalogScale(10, 10));
        List<UUID> dueCurrent = List.copyOf(versionIds.subList(0, 2));
        List<UUID> next = new ArrayList<>();
        Set<UUID> dueBands = new java.util.HashSet<>();
        transactions.executeWithoutResult(tx -> {
            for (UUID id : dueCurrent) {
                BillingPriceVersionEntity current = versionRepository.findById(id).orElseThrow();
                current.setEffectiveUntil(NOW);
                bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(id).forEach(band -> {
                    band.setEffectiveUntil(NOW);
                    dueBands.add(band.getId());
                });
                BillingPriceVersionEntity scheduled = versionRepository.saveAndFlush(BillingPriceVersionEntity.builder()
                        .productKind(BillingProductKind.PLAN).productKey(current.getProductKey())
                        .scopeKind(current.getScopeKind()).catalogRevision("1931-scale-due").revisionNo(2L)
                        .status(BillingPriceVersionStatus.SCHEDULED).effectiveFrom(NOW).provisionAttempts(0)
                        .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
                versionIds.add(scheduled.getId());
                next.add(scheduled.getId());
                BillingPriceBandVersionEntity scheduledBand = bandRepository.saveAndFlush(
                        BillingPriceBandVersionEntity.builder().productKind(BillingProductKind.PLAN)
                                .productKey(current.getProductKey()).scopeKind(current.getScopeKind())
                                .priceVersionId(scheduled.getId()).bandNo(1).minMembers(1).currency("JPY")
                                .stripePriceRef("price_it1931_due_" + UUID.randomUUID()).inputAmount(4_840L)
                                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000")
                                .taxMasterSnapshot("{}").amountExcludingTax(4_400L).taxAmount(440L)
                                .amountIncludingTax(4_840L).taxRateBasisPoints(1_000).taxNameSnapshot("消費税")
                                .includedInPrice(true).effectiveFrom(NOW).status(BillingPriceVersionStatus.SCHEDULED)
                                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
                bandIds.add(scheduledBand.getId());
                dueBands.add(scheduledBand.getId());
            }
        });
        Map<UUID, List<Object>> untouchedBefore = unchangedPriceRows(dueCurrent, next, dueBands);
        try (var capture = com.mannschaft.app.common.visibility.perf.SqlIntentCounter.captureCurrentThread()) {
            Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> result =
                    priceSelector.selectLegacyPlans(requested);
            assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(requested);
            for (EntitlementScopeKind scope : List.of(EntitlementScopeKind.USER, EntitlementScopeKind.TEAM)) {
                BillingPriceSelector.LegacyPlanSelection selection = result.get(
                        new BillingPriceSelector.PlanScopeKey(planKey, scope));
                assertThat(selection.state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.SELECTED);
                assertThat(selection.snapshot().bands()).extracting(BillingPriceSelector.LegacyPlanBandSnapshot::amountIncludingTax)
                        .containsExactly(4_840L);
            }
            assertThat(allSelectCount(capture.capturedSqls())).isLessThanOrEqualTo(3 + 4 * next.size());
        }
        transactions.executeWithoutResult(tx -> {
            assertThat(versionRepository.findAllById(dueCurrent)).hasSize(2)
                    .allSatisfy(version -> assertThat(version.getStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED));
            assertThat(versionRepository.findAllById(next)).hasSize(2)
                    .allSatisfy(version -> assertThat(version.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE));
        });
        assertThat(unchangedPriceRows(dueCurrent, next, dueBands)).isEqualTo(untouchedBefore);
    }

    private Map<UUID, List<Object>> unchangedPriceRows(List<UUID> current, List<UUID> next, Set<UUID> dueBands) {
        return transactions.execute(tx -> {
            Map<UUID, List<Object>> values = new java.util.HashMap<>();
            versionRepository.findAllById(versionIds).stream()
                    .filter(version -> !current.contains(version.getId()) && !next.contains(version.getId()))
                    .forEach(version -> values.put(version.getId(), List.of(version.getStatus(),
                            version.getLockVersion(), version.getUpdatedAt())));
            bandRepository.findAllById(bandIds).stream().filter(band -> !dueBands.contains(band.getId()))
                    .forEach(band -> values.put(band.getId(), List.of(band.getStatus(),
                            band.getLockVersion(), band.getUpdatedAt())));
            return values;
        });
    }

    /** AC13: 通常API上限input9,999,999×税率100%でも正常税込額はIntegerへ収まる。 */
    @Test
    void 正常API範囲の最大税込19999998を実DBから桁落ちなく読む() {
        transactions.executeWithoutResult(tx -> {
            BillingPriceBandVersionEntity band = bandRepository.findById(bandIds.getFirst()).orElseThrow();
            band.setInputAmount(9_999_999L);
            band.setTaxBehavior(BillingTaxBehavior.EXCLUSIVE);
            band.setAmountExcludingTax(9_999_999L);
            band.setTaxAmount(9_999_999L);
            band.setAmountIncludingTax(19_999_998L);
            band.setTaxRateBasisPoints(10_000);
            band.setIncludedInPrice(false);
        });
        assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)
                .orElseThrow().bands().getFirst().getAmountIncludingTax()).isEqualTo(19_999_998L);

        assertThat(priceResolver.resolveMonthlyPriceJpy(
                EntitlementScopeKind.USER, 1L, ContractKind.PLAN, planKey, null)).isEqualTo(19_999_998);
        assertThat(ownedCatalog().getBaseMonthlyPriceJpy()).isEqualTo(19_999_998);
    }

    /** 前段due空から公開履歴なしを推定してはならない。固定now後の他TX公開も観測する。 */
    @Test
    void due空の後に別commitで初公開と改定が起きても旧0へ戻さない() {
        removeActivePrices();
        setLegacyFree();
        SnapshotObservation observation = new SnapshotObservation();
        observation.afterDue = () -> commitElsewhere(() -> {
            saveObservedVersion(planKey, EntitlementScopeKind.USER, 1L, BillingPriceVersionStatus.RETIRED,
                    NOW.minusSeconds(60), NOW.plusSeconds(1), null);
            saveObservedVersion(planKey, EntitlementScopeKind.USER, 2L, BillingPriceVersionStatus.ACTIVE,
                    NOW.plusSeconds(1), null, ACTIVE_PRICE);
        });
        SnapshotInterleaveHook.CURRENT.set(observation);

        assertNotConfigured(EntitlementScopeKind.USER, 1L);

        assertThat(observation.dueReturned).isTrue();
        assertThat(observation.dueResult).isEmpty();
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"retiredMarker", "expiredActive"})
    void 適格ACTIVEは履歴marker又は期限外ACTIVEがあっても既存scalarと一致する(String extra) {
        transactions.executeWithoutResult(tx -> saveObservedVersion(planKey, EntitlementScopeKind.USER, 2L,
                "retiredMarker".equals(extra) ? BillingPriceVersionStatus.RETIRED : BillingPriceVersionStatus.ACTIVE,
                NOW.minusSeconds(60), NOW.minusSeconds(1), null));
        BillingPriceSelector.SelectedPrice scalar = priceSelector
                .selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER).orElseThrow();

        BillingPriceSelector.LegacyPlanSelection result = priceSelector.selectLegacyPlans(
                Set.of(new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER)))
                .get(new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER));

        assertThat(result.state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.SELECTED);
        assertThat(result.snapshot()).isNotNull();
        assertThat(result.snapshot().priceVersionId()).isEqualTo(scalar.revision().getId());
        assertThat(result.snapshot().bands().getFirst().amountIncludingTax()).isEqualTo(ACTIVE_PRICE);
    }

    @Test
    void 有効ACTIVEが複数なら既存scalarと同じく販売不能にする() {
        transactions.executeWithoutResult(tx -> saveObservedVersion(planKey, EntitlementScopeKind.USER, 2L,
                BillingPriceVersionStatus.ACTIVE, EFFECTIVE_FROM, null, ACTIVE_PRICE));
        assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)).isEmpty();
        BillingPriceSelector.PlanScopeKey key = new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER);

        BillingPriceSelector.LegacyPlanSelection result = priceSelector.selectLegacyPlans(Set.of(key)).get(key);

        assertThat(result.state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE);
        assertThat(result.snapshot()).isNull();
    }

    @Test
    void RETIRED履歴が多数でも非ACTIVEの親markerは要求key当たり最大1行である() {
        transactions.executeWithoutResult(tx -> {
            versionRepository.findById(versionIds.getFirst()).orElseThrow().setStatus(BillingPriceVersionStatus.RETIRED);
            bandRepository.findById(bandIds.getFirst()).orElseThrow().setStatus(BillingPriceVersionStatus.RETIRED);
            for (long revision = 2; revision <= 20; revision++) {
                saveObservedVersion(planKey, EntitlementScopeKind.USER, revision, BillingPriceVersionStatus.RETIRED,
                        EFFECTIVE_FROM, NOW.minusSeconds(1), null);
            }
        });
        SnapshotObservation observation = new SnapshotObservation();
        SnapshotInterleaveHook.CURRENT.set(observation);
        BillingPriceSelector.PlanScopeKey key = new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER);

        BillingPriceSelector.LegacyPlanSelection result = snapshotReader.readActive(Set.of(key), NOW).get(key);

        assertThat(result.state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE);
        assertThat(result.snapshot()).isNull();
        assertThat(observation.parentRowCounts).containsExactly(1);
    }

    @Test
    void PRIMARYの親子読取の間で別commitが改定してもsnapshotを混在させない() {
        UUID originalParent = versionIds.getFirst();
        UUID originalBand = bandIds.getFirst();
        SnapshotObservation observation = new SnapshotObservation();
        observation.afterParents = () -> commitElsewhere(() -> {
            versionRepository.findById(originalParent).orElseThrow().setStatus(BillingPriceVersionStatus.RETIRED);
            bandRepository.findById(originalBand).orElseThrow().setStatus(BillingPriceVersionStatus.RETIRED);
            saveObservedVersion(planKey, EntitlementScopeKind.USER, 2L, BillingPriceVersionStatus.ACTIVE,
                    NOW, null, 4_840L);
        });
        SnapshotInterleaveHook.CURRENT.set(observation);
        BillingPriceSelector.PlanScopeKey key = new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER);
        {
            BillingPriceSelector.LegacyPlanSelection result = priceSelector.selectLegacyPlans(Set.of(key)).get(key);

            assertThat(observation.parentsReturned).isTrue();
            assertThat(result.state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.SELECTED);
            assertThat(result.snapshot().priceVersionId()).isEqualTo(originalParent);
            assertThat(result.snapshot().bands()).containsExactly(new BillingPriceSelector.LegacyPlanBandSnapshot(
                    originalBand, 1, 1, null, ACTIVE_PRICE));
        }
        transactions.executeWithoutResult(tx -> {
            assertThat(versionRepository.findById(originalParent).orElseThrow().getStatus())
                    .isEqualTo(BillingPriceVersionStatus.RETIRED);
            assertThat(bandRepository.findById(originalBand).orElseThrow().getStatus())
                    .isEqualTo(BillingPriceVersionStatus.RETIRED);
        });
    }

    @Test
    void 要求keyとscopeのIN直積に含まれる非要求pairは昇格しない() {
        String otherPlan = "I1931X-" + UUID.randomUUID().toString().substring(0, 16);
        UUID[] excluded = new UUID[2];
        transactions.executeWithoutResult(tx -> {
            additionalPlans.add(otherPlan);
            planRepository.saveAndFlush(PlanEntity.builder().planKey(otherPlan).baseMonthlyPriceJpy(0)
                    .displayNameKey("it1931").descriptionKey("it1931").sortOrder(0).enabled(true).build());
            saveObservedVersion(otherPlan, EntitlementScopeKind.TEAM, 1L, BillingPriceVersionStatus.ACTIVE,
                    EFFECTIVE_FROM, null, ACTIVE_PRICE);
            excluded[0] = saveObservedVersion(otherPlan, EntitlementScopeKind.USER, 1L,
                    BillingPriceVersionStatus.SCHEDULED, NOW, null, ACTIVE_PRICE);
            excluded[1] = saveObservedVersion(planKey, EntitlementScopeKind.TEAM, 2L,
                    BillingPriceVersionStatus.SCHEDULED, NOW, null, ACTIVE_PRICE);
        });
        Set<BillingPriceSelector.PlanScopeKey> requested = Set.of(
                new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER),
                new BillingPriceSelector.PlanScopeKey(otherPlan, EntitlementScopeKind.TEAM));

        Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> result =
                priceSelector.selectLegacyPlans(requested);

        assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(requested);
        assertThat(result.values()).allSatisfy(selection ->
                assertThat(selection.state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.SELECTED));
        transactions.executeWithoutResult(tx -> assertThat(versionRepository.findAllById(List.of(excluded)))
                .hasSize(2).allSatisfy(version -> {
                    assertThat(version.getStatus()).isEqualTo(BillingPriceVersionStatus.SCHEDULED);
                    assertThat(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(version.getId()))
                            .hasSize(1).allSatisfy(band ->
                                    assertThat(band.getStatus()).isEqualTo(BillingPriceVersionStatus.SCHEDULED));
                }));
    }

    private UUID saveObservedVersion(String key, EntitlementScopeKind scope, long revision,
                                     BillingPriceVersionStatus status, Instant from, Instant until, Long amount) {
        BillingPriceVersionEntity parent = versionRepository.saveAndFlush(BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(key).scopeKind(scope)
                .catalogRevision("1931-observe-" + revision).revisionNo(revision).status(status)
                .effectiveFrom(from).effectiveUntil(until).provisionAttempts(0)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
        versionIds.add(parent.getId());
        if (amount != null) {
            long excluding = amount == 4_840L ? 4_400L : 3_300L;
            long tax = amount - excluding;
            bandIds.add(bandRepository.saveAndFlush(BillingPriceBandVersionEntity.builder()
                    .productKind(BillingProductKind.PLAN).productKey(key).scopeKind(scope)
                    .priceVersionId(parent.getId()).bandNo(1).minMembers(1).currency("JPY")
                    .stripePriceRef("price_it1931_observe_" + UUID.randomUUID()).inputAmount(amount)
                    .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                    .amountExcludingTax(excluding).taxAmount(tax).amountIncludingTax(amount).taxRateBasisPoints(1_000)
                    .taxNameSnapshot("消費税").includedInPrice(true).effectiveFrom(from).effectiveUntil(until)
                    .status(status).creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build()).getId());
        }
        return parent.getId();
    }

    private void commitElsewhere(Runnable fixtureMutation) {
        java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            writer.submit(() -> transactions.executeWithoutResult(tx -> fixtureMutation.run()))
                    .get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("所有fixtureの別TX commitに失敗しました", exception);
        } finally {
            writer.shutdownNow();
            try {
                assertThat(writer.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("所有fixture writerの終了待ちが中断されました", exception);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SnapshotObservationConfiguration {
        @Bean SnapshotInterleaveHook snapshotInterleaveHook() { return new SnapshotInterleaveHook(); }
    }

    /** 実処理復帰後だけの同期hook。内部返値を差し替えず、DB改定は別threadの別commit。 */
    @Aspect
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    static class SnapshotInterleaveHook {
        private static final ThreadLocal<SnapshotObservation> CURRENT = new ThreadLocal<>();

        @Around("execution(* com.mannschaft.app.billing.LegacyPlanPriceSnapshotReader.readDueKeys(..))")
        Object afterDue(ProceedingJoinPoint invocation) throws Throwable {
            Object result = invocation.proceed();
            SnapshotObservation observation = CURRENT.get();
            if (observation != null) {
                observation.dueReturned = true;
                observation.dueResult = (Set<?>) result;
                Runnable action = observation.afterDue;
                observation.afterDue = null;
                if (action != null) action.run();
            }
            return result;
        }

        @Around("execution(* com.mannschaft.app.billing.LegacyPlanPriceSnapshotReader.readActive(..))")
        Object duringActive(ProceedingJoinPoint invocation) throws Throwable {
            SnapshotObservation observation = CURRENT.get();
            if (observation != null) observation.activeWindow = true;
            try { return invocation.proceed(); }
            finally { if (observation != null) observation.activeWindow = false; }
        }

        @Around("execution(* com.mannschaft.app.billing.BillingPriceVersionRepository.*(..))")
        Object afterParents(ProceedingJoinPoint invocation) throws Throwable {
            Object result = invocation.proceed();
            SnapshotObservation observation = CURRENT.get();
            if (observation != null && observation.activeWindow && result instanceof List<?> rows
                    && !rows.isEmpty()) {
                // r2のcanonical projectionも同じ親query。返値型をEntityへ限定しない。
                observation.parentsReturned = true;
                observation.parentRowCounts.add(rows.size());
                Runnable action = observation.afterParents;
                observation.afterParents = null;
                if (action != null) action.run();
            }
            return result;
        }
    }

    static class SnapshotObservation {
        private Runnable afterDue;
        private Runnable afterParents;
        private boolean dueReturned;
        private boolean parentsReturned;
        private boolean activeWindow;
        private Set<?> dueResult;
        private final List<Integer> parentRowCounts = new ArrayList<>();
    }

    /** AC02/03の既存互換。enabled=falseを新価格の存在でcatalogへ復活させない。 */
    @Test
    void 無効planは新ACTIVEがあっても会員catalogに表示しない() {
        transactions.executeWithoutResult(tx -> planRepository.findById(planKey).orElseThrow().setEnabled(false));

        assertThat(catalogService.getCatalog().getPlans()).noneMatch(plan -> planKey.equals(plan.getPlanKey()));
    }

    private void makeActiveInvalid(String defect) {
        transactions.executeWithoutResult(tx -> {
            BillingPriceBandVersionEntity band = bandRepository.findById(bandIds.getFirst()).orElseThrow();
            switch (defect) {
                case "emptyTaxName" -> band.setTaxNameSnapshot("");
                case "inconsistentTotal" -> band.setAmountIncludingTax(ACTIVE_PRICE + 1);
                case "retiredBand" -> band.setStatus(BillingPriceVersionStatus.RETIRED);
                case "emptyBands" -> bandRepository.delete(band);
                case "emptyTaxCode" -> band.setTaxCodeSnapshot("");
                case "blankStripe" -> band.setStripePriceRef(" ");
                case "overlappingBands" -> {
                    band.setMaxMembers(20);
                    saveMemberBand(versionRepository.findById(band.getPriceVersionId()).orElseThrow(),
                            2, 20, null, 4_840L, 4_400L, 440L);
                }
                default -> throw new IllegalArgumentException(defect);
            }
        });
    }

    /** V196 CI collation: canonical Planとraw lower価格の比較をJava equalsで失わない。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"catalog", "resolverCanonical", "resolverRaw", "batch"})
    void 大文字Planと小文字の適格価格は既存scalarと同じBを返す(String entry) {
        removeActivePrices();
        setLegacyFree();
        String rawKey = planKey.toLowerCase(java.util.Locale.ROOT);
        transactions.executeWithoutResult(tx -> saveObservedVersion(rawKey, EntitlementScopeKind.USER, 1L,
                BillingPriceVersionStatus.ACTIVE, EFFECTIVE_FROM, null, ACTIVE_PRICE));
        BillingPriceSelector.SelectedPrice scalar = priceSelector
                .selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER).orElseThrow();
        assertThat(scalar.revision().getProductKey()).isEqualTo(rawKey);
        assertThat(scalar.bands().getFirst().getAmountIncludingTax()).isEqualTo(ACTIVE_PRICE);

        switch (entry) {
            case "catalog" -> assertThat(ownedCatalog().getBaseMonthlyPriceJpy()).isEqualTo((int) ACTIVE_PRICE);
            case "resolverCanonical", "resolverRaw" -> assertThat(priceResolver.resolveMonthlyPriceJpy(
                    EntitlementScopeKind.USER, 1L, ContractKind.PLAN,
                    "resolverRaw".equals(entry) ? rawKey : planKey, null)).isEqualTo((int) ACTIVE_PRICE);
            case "batch" -> {
                BillingPriceSelector.PlanScopeKey key = new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER);
                Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> selected =
                        priceSelector.selectLegacyPlans(Set.of(key));
                assertThat(selected).containsOnlyKeys(key);
                assertThat(selected.get(key).state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.SELECTED);
                assertThat(selected.get(key).snapshot().bands().getFirst().amountIncludingTax()).isEqualTo(ACTIVE_PRICE);
            }
            default -> throw new IllegalArgumentException(entry);
        }
    }

    /** modern陽性対照は別fixtureで実行し、legacy対象のdueを事前昇格して隠さない。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"modernScalar", "legacyResolver"})
    void 小文字のdue価格はcanonical要求から昇格され旧0へ戻らない(String entry) {
        removeActivePrices();
        setLegacyFree();
        String rawKey = planKey.toLowerCase(java.util.Locale.ROOT);
        UUID id = transactions.execute(tx -> saveObservedVersion(rawKey, EntitlementScopeKind.USER, 1L,
                BillingPriceVersionStatus.SCHEDULED, NOW, null, ACTIVE_PRICE));

        if ("modernScalar".equals(entry)) {
            assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)
                    .orElseThrow().bands().getFirst().getAmountIncludingTax()).isEqualTo(ACTIVE_PRICE);
        } else {
            assertThat(priceResolver.resolveMonthlyPriceJpy(
                    EntitlementScopeKind.USER, 1L, ContractKind.PLAN, planKey, null)).isEqualTo((int) ACTIVE_PRICE);
        }

        transactions.executeWithoutResult(tx -> {
            assertThat(versionRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);
            assertThat(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(id))
                    .hasSize(1).allSatisfy(band -> assertThat(band.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE));
        });
    }

    /** getterのfindById(alias)は観測のみ。今後使うquery lookupの保存keyを規範として確認する。 */
    @Test
    void raw別綴りの単一findAllByIdは保存canonicalkeyをhydrateする() {
        String alias = planKey.toLowerCase(java.util.Locale.ROOT);
        String findByIdKey = transactions.execute(tx -> planRepository.findById(alias)
                .map(PlanEntity::getPlanKey).orElseThrow());
        List<String> queriedKeys = transactions.execute(tx -> planRepository.findAllById(List.of(alias)).stream()
                .map(PlanEntity::getPlanKey).toList());
        System.out.println("IT1931_KEY_LOOKUP_OBSERVATION findByIdMatchesStored=" + planKey.equals(findByIdKey)
                + " findAllByIdMatchesStored=" + List.of(planKey).equals(queriedKeys));

        assertThat(queriedKeys).containsExactly(planKey);
    }

    /** 直接内部batchのraw aliasと不存在keyは、未公開無料ではなく販売不能へ閉じる。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(strings = {"rawAlias", "missing"})
    void 内部batchの非canonical又は不存在keyを無料扱いしない(String input) {
        removeActivePrices();
        setLegacyFree();
        String keyValue = "rawAlias".equals(input) ? planKey.toLowerCase(java.util.Locale.ROOT) : planKey + "-missing";
        BillingPriceSelector.PlanScopeKey key = new BillingPriceSelector.PlanScopeKey(keyValue, EntitlementScopeKind.USER);

        Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> result =
                priceSelector.selectLegacyPlans(Set.of(key));

        assertThat(result).containsOnlyKeys(key);
        assertThat(result.get(key).state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE);
        assertThat(result.get(key).snapshot()).isNull();
    }

    @Test
    void 大文字と小文字の有効ACTIVEは同canonicalへまとめて複数候補を拒否する() {
        setLegacyFree();
        transactions.executeWithoutResult(tx -> saveObservedVersion(planKey.toLowerCase(java.util.Locale.ROOT),
                EntitlementScopeKind.USER, 2L, BillingPriceVersionStatus.ACTIVE, EFFECTIVE_FROM, null, ACTIVE_PRICE));
        assertThat(priceSelector.selectNow(BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER)).isEmpty();
        BillingPriceSelector.PlanScopeKey key = new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER);

        Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> result =
                priceSelector.selectLegacyPlans(Set.of(key));

        assertThat(result).containsOnlyKeys(key);
        assertThat(result.get(key).state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.NOT_SELLABLE);
        assertThat(result.get(key).snapshot()).isNull();
    }

    private PlanItem ownedCatalog() {
        return catalogService.getCatalog().getPlans().stream()
                .filter(plan -> planKey.equals(plan.getPlanKey())).findFirst().orElseThrow();
    }

    private void assertNotConfigured(EntitlementScopeKind scope, Long scopeId) {
        assertThatThrownBy(() -> priceResolver.resolveMonthlyPriceJpy(scope, scopeId, ContractKind.PLAN, planKey, null))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);
    }

    private void removeActivePrices() {
        transactions.executeWithoutResult(tx -> {
            bandRepository.deleteAllById(bandIds);
            bandRepository.flush();
            versionRepository.deleteAllById(versionIds);
            versionRepository.flush();
        });
    }

    private void splitActiveMemberBands(EntitlementScopeKind scope, boolean gap) {
        transactions.executeWithoutResult(tx -> {
            BillingPriceBandVersionEntity first = bandRepository.findAllById(bandIds).stream()
                    .filter(band -> band.getScopeKind() == scope).findFirst().orElseThrow();
            first.setMaxMembers(20);
            BillingPriceVersionEntity version = versionRepository.findById(first.getPriceVersionId()).orElseThrow();
            saveMemberBand(version, 2, gap ? 22 : 21, 50, 4_840L, 4_400L, 440L);
            saveMemberBand(version, 10, 51, null, 6_050L, 5_500L, 550L);
        });
    }

    private void saveMemberBand(BillingPriceVersionEntity version, int no, int min, Integer max,
                                long total, long excluding, long tax) {
        BillingPriceBandVersionEntity band = bandRepository.saveAndFlush(BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(version.getScopeKind())
                .priceVersionId(version.getId()).bandNo(no).minMembers(min).maxMembers(max).currency("JPY")
                .stripePriceRef("price_it1931_" + UUID.randomUUID()).inputAmount(total)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(excluding).taxAmount(tax).amountIncludingTax(total).taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税").includedInPrice(true).effectiveFrom(EFFECTIVE_FROM)
                .status(BillingPriceVersionStatus.ACTIVE)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
        bandIds.add(band.getId());
    }

    private Long prepareMembers(EntitlementScopeKind scope, int members) {
        return transactions.execute(tx -> {
            String slug = "i1931-" + UUID.randomUUID().toString().substring(0, 16);
            Long scopeId;
            if (scope == EntitlementScopeKind.TEAM) {
                teamId = teamRepository.saveAndFlush(TeamEntity.builder().name("1931人数試練")
                        .slug(slug).visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build())
                        .getId();
                scopeId = teamId;
            } else {
                organizationId = organizationRepository.saveAndFlush(OrganizationEntity.builder().name("1931人数試練")
                        .slug(slug).orgType(OrganizationEntity.OrgType.OTHER)
                        .visibility(OrganizationEntity.Visibility.PRIVATE)
                        .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build())
                        .getId();
                scopeId = organizationId;
            }
            for (int index = 0; index < members; index++) {
                Long userId = userRepository.saveAndFlush(UserEntity.builder()
                        .email(slug + "-" + index + "@example.com").lastName("試練").firstName("1931")
                        .displayName("1931人数試練").status(UserEntity.UserStatus.ACTIVE)
                        .locale("ja").timezone("Asia/Tokyo").isSearchable(true).build()).getId();
                userIds.add(userId);
                membershipIds.add(membershipRepository.saveAndFlush(MembershipEntity.builder()
                        .userId(userId).scopeId(scopeId).scopeType(scope == EntitlementScopeKind.TEAM
                                ? ScopeType.TEAM : ScopeType.ORGANIZATION)
                        .joinedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).build()).getId());
            }
            return scopeId;
        });
    }
}
