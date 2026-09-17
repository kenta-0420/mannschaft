package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.mannschaft.app.billing.BillingChangePreviewEntity;
import com.mannschaft.app.billing.BillingChangePreviewRepository;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingPlanChangeGateway;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.billing.api.dto.BillingChangePreviewRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Billing Center PR6b-1 A群 — band 解決（AC-4 / AC-17 / AC-19 / AC-20b・c / AC-24）の純 UT。
 *
 * <p>IT（{@code BillingChangePreviewApiRedIT}）は Docker 必須で開発環境では1本も走らない。
 * 「同じ商品に ACTIVE band が複数世代ある」という CI で実際に起きていた状況を、DB なしで
 * 再現して固定するのが本テストの役目である。</p>
 */
@DisplayName("PR6b-1 事前見積りの band 解決（現行 revision 一本・旧世代へ落ちない）")
class BillingPlanChangePreviewBandResolutionTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final UUID CONTRACT_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final long ACTOR_ID = 42L;

    private BillingContractRepository contractRepository;
    private BillingChangePreviewRepository previewRepository;
    private BillingPriceBandVersionRepository bandRepository;
    private BillingPriceVersionRepository priceVersionRepository;
    private BillingAccessGuard accessGuard;
    private ScopeMemberCountService memberCountService;
    private BillingPlanChangeGateway planChangeGateway;
    private BillingPlanChangePreviewService service;

    private BillingPriceVersionEntity currentFullRevision;
    private BillingPriceVersionEntity currentBasicRevision;
    private BillingPriceBandVersionEntity currentFullBand;
    private BillingPriceBandVersionEntity currentBasicBand;

    @BeforeEach
    void setUp() {
        contractRepository = mock(BillingContractRepository.class);
        previewRepository = mock(BillingChangePreviewRepository.class);
        bandRepository = mock(BillingPriceBandVersionRepository.class);
        priceVersionRepository = mock(BillingPriceVersionRepository.class);
        accessGuard = mock(BillingAccessGuard.class);
        memberCountService = mock(ScopeMemberCountService.class);
        planChangeGateway = mock(BillingPlanChangeGateway.class);
        service = new BillingPlanChangePreviewService(
                contractRepository, previewRepository,
                new BillingCurrentBandResolver(priceVersionRepository, bandRepository),
                accessGuard, memberCountService, planChangeGateway, new ObjectMapper(),
                Clock.fixed(NOW, ZONE));

        currentBasicRevision = revision("BASIC");
        currentFullRevision = revision("FULL");
        currentBasicBand = band(currentBasicRevision, 1_200L, "price_basic");
        currentFullBand = band(currentFullRevision, 3_300L, "price_full");

        given(accessGuard.canManageByActorId(ACTOR_ID, EntitlementScopeKind.USER, ACTOR_ID))
                .willReturn(true);
        given(memberCountService.countActiveMembers(EntitlementScopeKind.USER, ACTOR_ID))
                .willReturn(1);
        given(contractRepository.findByIdAndDeletedAtIsNull(CONTRACT_ID))
                .willReturn(Optional.of(contract(currentBasicBand.getId())));
        given(bandRepository.findByIdAndDeletedAtIsNull(currentBasicBand.getId()))
                .willReturn(Optional.of(currentBasicBand));
        givenActiveRevision("BASIC", currentBasicRevision);
        givenActiveRevision("FULL", currentFullRevision);
        givenBands(currentBasicRevision, currentBasicBand);
        givenBands(currentFullRevision, currentFullBand);
        given(planChangeGateway.previewPlanChange(any())).willReturn(
                new BillingPlanChangeGateway.PlanChangeQuote(
                        "JPY", 777L, 707L, 70L, "消費税", 1_000,
                        NOW, NOW.plus(20, ChronoUnit.DAYS), NOW));
    }

    @Test
    @DisplayName("AC-4: 旧世代の ACTIVE band が残っていても、現行 revision の band を使う")
    void 旧世代が残っていても現行revisionのbandを使う() {
        BillingPriceVersionEntity staleRevision = revision("FULL");
        BillingPriceBandVersionEntity staleBand = band(staleRevision, 9_900L, "price_full_stale");
        // 現行 revision の解決結果は「最新1本」。band を直接横断検索していたら stale が混ざる。
        given(priceVersionRepository.findEffectiveCandidates(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER,
                List.of(BillingPriceVersionStatus.ACTIVE), NOW))
                .willReturn(List.of(currentFullRevision, staleRevision));
        givenBands(staleRevision, staleBand);

        service.preview(ACTOR_ID, CONTRACT_ID, request("FULL"), "{}");

        BillingChangePreviewEntity saved = savedPreview();
        assertThat(saved.getToPriceBandVersionId()).isEqualTo(currentFullBand.getId());
        assertThat(saved.getFromPriceBandVersionId()).isEqualTo(currentBasicBand.getId());
        assertThat(saved.getMemberCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-20b: 現行 revision の band が RETIRED なら、旧世代へ落ちずに 409")
    void 現行bandがRETIREDなら旧世代へ落ちない() {
        currentFullBand.setStatus(BillingPriceVersionStatus.RETIRED);

        assertThatThrownBy(() -> service.preview(ACTOR_ID, CONTRACT_ID, request("FULL"), "{}"))
                .isInstanceOf(BillingConflictException.class);
        verify(planChangeGateway, never()).previewPlanChange(any());
    }

    @Test
    @DisplayName("AC-20c: 現行 band の Stripe Price ref が無ければ Stripe を呼ばずに 409")
    void PriceRefが無ければStripeを呼ばない() {
        currentFullBand.setStripePriceRef(null);

        assertThatThrownBy(() -> service.preview(ACTOR_ID, CONTRACT_ID, request("FULL"), "{}"))
                .isInstanceOf(BillingConflictException.class);
        verify(planChangeGateway, never()).previewPlanChange(any());
    }

    @Test
    @DisplayName("AC-19: 契約の band が NULL なら人数から解決して from に埋める")
    void 契約のbandがNULLなら人数から解決する() {
        given(contractRepository.findByIdAndDeletedAtIsNull(CONTRACT_ID))
                .willReturn(Optional.of(contract(null)));

        service.preview(ACTOR_ID, CONTRACT_ID, request("FULL"), "{}");

        assertThat(savedPreview().getFromPriceBandVersionId()).isEqualTo(currentBasicBand.getId());
    }

    @Test
    @DisplayName("AC-19b: 契約の band が NULL で人数がどの band にも当たらなければ 409（500 にしない）")
    void 人数がbandに当たらなければ409() {
        given(contractRepository.findByIdAndDeletedAtIsNull(CONTRACT_ID))
                .willReturn(Optional.of(contract(null)));
        currentBasicBand.setMinMembers(50);
        currentBasicBand.setMaxMembers(100);

        assertThatThrownBy(() -> service.preview(ACTOR_ID, CONTRACT_ID, request("FULL"), "{}"))
                .isInstanceOf(BillingConflictException.class);
    }

    @Test
    @DisplayName("AC-24: 現行 band が同額なら upgrade として扱わず 409")
    void 同額は409() {
        currentFullBand.setAmountIncludingTax(1_200L);

        assertThatThrownBy(() -> service.preview(ACTOR_ID, CONTRACT_ID, request("FULL"), "{}"))
                .isInstanceOf(BillingConflictException.class);
        verify(planChangeGateway, never()).previewPlanChange(any());
    }

    @Test
    @DisplayName("AC-17: Spring Boot 既定の mapper でも priceBandVersionId は黙って捨てられず例外になる")
    void 価格IDを送ると本文パースで弾かれる() {
        // Spring Boot の既定（fail-on-unknown-properties=false）を再現した mapper。
        ObjectMapper springBootLike = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        assertThatThrownBy(() -> springBootLike.readValue(
                "{\"toProductKind\":\"PLAN\",\"toProductKey\":\"FULL\",\"version\":0,"
                        + "\"priceBandVersionId\":\"" + UUID.randomUUID() + "\"}",
                BillingChangePreviewRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);

        // 陽性対照: 既知の項目だけなら同じ mapper で読める（全部 400 にしているのではない）。
        assertThatCode(() -> springBootLike.readValue(
                "{\"toProductKind\":\"PLAN\",\"toProductKey\":\"FULL\",\"version\":0}",
                BillingChangePreviewRequest.class)).doesNotThrowAnyException();
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private BillingChangePreviewEntity savedPreview() {
        ArgumentCaptor<BillingChangePreviewEntity> captor =
                ArgumentCaptor.forClass(BillingChangePreviewEntity.class);
        verify(previewRepository).save(captor.capture());
        return captor.getValue();
    }

    private void givenActiveRevision(String productKey, BillingPriceVersionEntity revision) {
        given(priceVersionRepository.findEffectiveCandidates(
                BillingProductKind.PLAN, productKey, EntitlementScopeKind.USER,
                List.of(BillingPriceVersionStatus.ACTIVE), NOW))
                .willReturn(List.of(revision));
    }

    private void givenBands(BillingPriceVersionEntity revision, BillingPriceBandVersionEntity... bands) {
        given(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revision.getId()))
                .willReturn(List.of(bands));
    }

    private BillingChangePreviewRequest request(String toProductKey) {
        return new BillingChangePreviewRequest(BillingProductKind.PLAN, toProductKey, 0L);
    }

    private BillingContractEntity contract(UUID bandId) {
        BillingContractEntity contract = BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.USER).scopeId(ACTOR_ID)
                .contractKind(ContractKind.PLAN).planKey("BASIC")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(1_200)
                .memberCountSnapshot(1)
                .priceBandVersionId(bandId)
                .billingCustomerId(CUSTOMER_ID)
                .pspSubscriptionRef("sub_ut")
                .currentPeriodEnd(LocalDateTime.ofInstant(NOW.plus(20, ChronoUnit.DAYS), ZONE))
                .version(0L)
                .build();
        contract.setId(CONTRACT_ID);
        return contract;
    }

    private BillingPriceVersionEntity revision(String productKey) {
        BillingPriceVersionEntity revision = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .productKey(productKey)
                .scopeKind(EntitlementScopeKind.USER)
                .status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(NOW.minus(30, ChronoUnit.DAYS))
                .build();
        revision.setId(UUID.randomUUID());
        return revision;
    }

    private BillingPriceBandVersionEntity band(
            BillingPriceVersionEntity revision, long amountIncludingTax, String stripePriceRef) {
        BillingPriceBandVersionEntity band = BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .productKey(revision.getProductKey())
                .scopeKind(EntitlementScopeKind.USER)
                .bandNo(1).minMembers(1).maxMembers(null)
                .priceVersionId(revision.getId())
                .stripePriceRef(stripePriceRef)
                .currency("JPY")
                .amountIncludingTax(amountIncludingTax)
                .taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税")
                .effectiveFrom(NOW.minus(30, ChronoUnit.DAYS))
                .status(BillingPriceVersionStatus.ACTIVE)
                .build();
        band.setId(UUID.randomUUID());
        return band;
    }
}
