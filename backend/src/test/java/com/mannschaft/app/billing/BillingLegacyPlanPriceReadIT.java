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
    @ValueSource(strings = {"emptyTaxName", "inconsistentTotal", "retiredBand", "emptyBands"})
    void 不適格な新価格は旧baseへfallbackせず未設定040で拒否する(String defect) {
        transactions.executeWithoutResult(tx -> {
            BillingPriceBandVersionEntity band = bandRepository.findById(bandIds.getFirst()).orElseThrow();
            switch (defect) {
                case "emptyTaxName" -> band.setTaxNameSnapshot("");
                case "inconsistentTotal" -> band.setAmountIncludingTax(ACTIVE_PRICE + 1);
                case "retiredBand" -> band.setStatus(BillingPriceVersionStatus.RETIRED);
                case "emptyBands" -> bandRepository.delete(band);
                default -> throw new IllegalArgumentException(defect);
            }
        });
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
    @ValueSource(strings = {"emptyTaxName", "inconsistentTotal", "retiredBand", "emptyBands"})
    void 旧明示0でも新ACTIVEの不適格を無料へfallbackしない(String defect) {
        setLegacyFree();
        transactions.executeWithoutResult(tx -> {
            BillingPriceBandVersionEntity band = bandRepository.findById(bandIds.getFirst()).orElseThrow();
            switch (defect) {
                case "emptyTaxName" -> band.setTaxNameSnapshot("");
                case "inconsistentTotal" -> band.setAmountIncludingTax(ACTIVE_PRICE + 1);
                case "retiredBand" -> band.setStatus(BillingPriceVersionStatus.RETIRED);
                case "emptyBands" -> bandRepository.delete(band);
                default -> throw new IllegalArgumentException(defect);
            }
        });
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

    /** 初回greenが正常の既存互換。seed FREEと所有明示0を実契約保存まで通す。 */
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void FREEと新公開価格なしの明示0は実無料契約を作成できる(boolean existingFree) {
        removeActivePrices();
        setLegacyFree();
        String targetPlan = existingFree ? "FREE" : planKey;
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
