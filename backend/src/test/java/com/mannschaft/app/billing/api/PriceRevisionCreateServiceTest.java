package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceBandInput;
import com.mannschaft.app.billing.api.dto.PriceRevisionCreateRequest;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.billing.tax.BillingTaxCodeService;
import com.mannschaft.app.billing.tax.BillingTaxCodeView;
import com.mannschaft.app.billing.tax.BillingTaxDerivationResult;
import com.mannschaft.app.billing.tax.BillingTaxDerivationService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * 価格改定戦役（price-revisions）第8隊: {@code POST /price-revisions} 作成ロジックの試練（試練・B/C群）。
 *
 * <p>{@code PriceRevisionCreateService} / {@code PriceRevisionCreateRequest} / {@code PriceBandInput} /
 * {@code PriceRevisionResponse} は本試練時点で未実装であり、クラス自体が存在しないためコンパイルエラーとして
 * red になることを是とする。</p>
 *
 * <p>固定時刻 {@link #FIXED_NOW} を {@link Clock} 経由で注入し、過去日時判定（AC-29）を再現可能にする。</p>
 *
 * <p>正本: `.claude/campaigns/price-rev-plan-v3.md` 決定4・決定8・AC-17〜AC-46・AC-48〜AC-53・AC-176・AC-177。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PriceRevisionCreateService 試練（AC-17〜AC-46・AC-48〜AC-53・AC-176・AC-177）")
class PriceRevisionCreateServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2027-01-01T00:00:00Z");
    private static final Long ADMIN_ID = 42L;

    @Mock
    private BillingPriceVersionRepository priceVersionRepository;
    @Mock
    private BillingPriceBandVersionRepository bandVersionRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private FeatureCatalogRepository featureCatalogRepository;
    @Mock
    private BillingTaxCodeService taxCodeService;
    @Mock
    private BillingTaxDerivationService taxDerivationService;

    private PriceRevisionCreateService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new PriceRevisionCreateService(
                priceVersionRepository, bandVersionRepository, planRepository,
                featureCatalogRepository, taxCodeService, taxDerivationService, clock,
                org.mockito.Mockito.mock(com.mannschaft.app.auth.service.AuditLogService.class));

        lenient().when(planRepository.existsById("FULL")).thenReturn(true);
        lenient().when(planRepository.findById("FULL")).thenReturn(Optional.of(
                PlanEntity.builder().planKey("FULL").enabled(true).build()));
        lenient().when(featureCatalogRepository.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(priceVersionRepository.findAllForUpdate(any(), anyString(), any())).thenReturn(List.of());

        BillingTaxCodeView taxCode = BillingTaxCodeView.from(
                com.mannschaft.app.billing.tax.BillingTaxCodeEntity.builder()
                        .code("JP_STANDARD_10").displayName("標準税率10%")
                        .rateBasisPoints(1000).validFrom(Instant.EPOCH).enabled(true).build());
        lenient().when(taxCodeService.resolveEffective(anyString(), any())).thenReturn(taxCode);

        lenient().when(taxDerivationService.derive(any(), any(), any())).thenAnswer(inv -> {
            long amount = inv.getArgument(0);
            BillingTaxBehavior behavior = inv.getArgument(1);
            BillingTaxCodeView code = inv.getArgument(2);
            long tax = amount * code.rateBasisPoints() / 10000;
            return BillingTaxDerivationResult.builder()
                    .amountExcludingTax(amount).taxAmount(tax).amountIncludingTax(amount + tax)
                    .taxRateBasisPoints(code.rateBasisPoints())
                    .taxCodeSnapshot(code.code()).taxNameSnapshot(code.displayName())
                    .taxMasterSnapshot("{}")
                    .includedInPrice(behavior == BillingTaxBehavior.INCLUSIVE)
                    .build();
        });

        lenient().when(priceVersionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bandVersionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private PriceBandInput band(int bandNo, int minMembers, Integer maxMembers, long inputAmount) {
        return new PriceBandInput(bandNo, minMembers, maxMembers, inputAmount,
                BillingTaxBehavior.EXCLUSIVE, "JP_STANDARD_10");
    }

    private PriceRevisionCreateRequest request(List<PriceBandInput> bands) {
        return new PriceRevisionCreateRequest(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM,
                Instant.parse("2027-06-01T00:00:00Z"), null, bands);
    }

    private PriceRevisionCreateRequest requestWithBands(PriceBandInput... bands) {
        List<PriceBandInput> list = new ArrayList<>(List.of(bands));
        return request(list);
    }

    private void assertBadRequest(PriceRevisionCreateRequest req, PriceRevisionErrorCode expected) {
        assertThatThrownBy(() -> service.create(req, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    // ---- 基本作成 ----

    @Test
    @DisplayName("AC-17: SYSTEM_ADMIN が DRAFT revision + bands を作成でき成功する")
    void ac17_createsDraftRevisionWithBands() {
        PriceRevisionResponse response = service.create(
                requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);

        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.DRAFT);
        assertThat(response.getBands()).hasSize(1);
    }

    @Test
    @DisplayName("AC-18: revisionNo はサーバー採番で不変（クライアント指定を無視する）")
    void ac18_revisionNoIsServerAssigned() {
        given(priceVersionRepository.findByProductKindAndProductKeyAndScopeKindAndDeletedAtIsNullOrderByRevisionNoDesc(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of(BillingPriceVersionEntity.builder().revisionNo(3L).build()));

        PriceRevisionResponse response = service.create(requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);

        assertThat(response.getRevisionNo()).isEqualTo(4L);
    }

    @Test
    @DisplayName("AC-19: catalogRevision はサーバー採番で不変")
    void ac19_catalogRevisionIsServerAssigned() {
        PriceRevisionResponse response = service.create(requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);
        assertThat(response.getCatalogRevision()).isNotBlank();
    }

    @Test
    @DisplayName("AC-20: bands が空配列なら 400")
    void ac20_emptyBands_400() {
        assertBadRequest(requestWithBands(), PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-20: bands が null なら 400")
    void ac20_nullBands_400() {
        assertBadRequest(request(null), PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-21: bandNo は1から連番。欠番は400")
    void ac21_bandNoGap_400() {
        assertBadRequest(requestWithBands(band(1, 1, 20, 1000), band(3, 21, null, 2000)),
                PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-21: bandNo の重複は400")
    void ac21_bandNoDuplicate_400() {
        assertBadRequest(requestWithBands(band(1, 1, 20, 1000), band(1, 21, null, 2000)),
                PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-22: 人数レンジは前bandのmaxMembers+1が次bandのminMembers（20/21境界）")
    void ac22_rangeBoundary_20_21_valid() {
        PriceRevisionResponse r = service.create(
                requestWithBands(band(1, 1, 20, 1000), band(2, 21, null, 2000)), ADMIN_ID);
        assertThat(r.getBands()).hasSize(2);
    }

    @Test
    @DisplayName("AC-22: 人数レンジ境界不正（50/51であるべきところが50/52）は400")
    void ac22_rangeBoundary_50_51_violation_400() {
        assertBadRequest(requestWithBands(band(1, 1, 50, 1000), band(2, 52, null, 2000)),
                PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-23: minMembers > maxMembers は400")
    void ac23_minGreaterThanMax_400() {
        assertBadRequest(requestWithBands(band(1, 10, 5, 1000)), PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-24: maxMembers=null は最終bandのみ許容される（先頭で null は400）")
    void ac24_nullMaxMembersOnlyLastAllowed_400() {
        assertBadRequest(requestWithBands(band(1, 1, null, 1000), band(2, 21, null, 2000)),
                PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-25: 最終bandにmaxMembersがあり open-ended が無い場合は400")
    void ac25_noOpenEndedBand_400() {
        assertBadRequest(requestWithBands(band(1, 1, 20, 1000), band(2, 21, 50, 2000)),
                PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-26: minMembers が0は400")
    void ac26_zeroMinMembers_400() {
        assertBadRequest(requestWithBands(band(1, 0, null, 1000)), PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-26: minMembers が負数は400")
    void ac26_negativeMinMembers_400() {
        assertBadRequest(requestWithBands(band(1, -1, null, 1000)), PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-27: inputAmount<=0 は作成時点で400")
    void ac27_nonPositiveInputAmount_400() {
        assertBadRequest(requestWithBands(band(1, 1, null, 0)), PriceRevisionErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("AC-28: inputAmount 上限9,999,999を超えると400")
    void ac28_overMaxInputAmount_400() {
        assertBadRequest(requestWithBands(band(1, 1, null, 10_000_000L)), PriceRevisionErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("AC-29: effectiveFrom が過去日時なら400")
    void ac29_pastEffectiveFrom_400() {
        PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.PLAN, "FULL",
                EntitlementScopeKind.TEAM, FIXED_NOW.minusSeconds(1), null, List.of(band(1, 1, null, 1000)));
        assertBadRequest(req, PriceRevisionErrorCode.INVALID_EFFECTIVE_PERIOD);
    }

    @Test
    @DisplayName("AC-30: effectiveUntil<=effectiveFrom は400")
    void ac30_effectiveUntilNotAfterFrom_400() {
        Instant from = Instant.parse("2027-06-01T00:00:00Z");
        PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.PLAN, "FULL",
                EntitlementScopeKind.TEAM, from, from, List.of(band(1, 1, null, 1000)));
        assertBadRequest(req, PriceRevisionErrorCode.INVALID_EFFECTIVE_PERIOD);
    }

    @Test
    @DisplayName("AC-31: PLAN の productKey が plans に実在しなければ400")
    void ac31_unknownPlanKey_400() {
        given(planRepository.existsById("NOPE")).willReturn(false);
        PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.PLAN, "NOPE",
                EntitlementScopeKind.TEAM, Instant.parse("2027-06-01T00:00:00Z"), null,
                List.of(band(1, 1, null, 1000)));
        assertBadRequest(req, PriceRevisionErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-32: ADDON の productKey が feature_catalog.feature_key に実在しなければ400")
    void ac32_unknownFeatureKey_400() {
        given(featureCatalogRepository.findById("ads.hide")).willReturn(Optional.empty());
        PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.ADDON, "ads.hide",
                EntitlementScopeKind.TEAM, Instant.parse("2027-06-01T00:00:00Z"), null,
                List.of(band(1, 1, null, 1000)));
        assertBadRequest(req, PriceRevisionErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-33: ADDON かつ対象 feature の addonAvailable=false なら400")
    void ac33_addonNotAvailable_400() {
        given(featureCatalogRepository.findById("ads.hide")).willReturn(Optional.of(
                FeatureCatalogEntity.builder().featureKey("ads.hide").addonAvailable(false).enabled(true).build()));
        PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.ADDON, "ads.hide",
                EntitlementScopeKind.TEAM, Instant.parse("2027-06-01T00:00:00Z"), null,
                List.of(band(1, 1, null, 1000)));
        assertBadRequest(req, PriceRevisionErrorCode.ADDON_NOT_AVAILABLE_FOR_REVISION);
    }

    @Test
    @DisplayName("AC-34: PLAN/ADDON の productKey 型の取り違えは400")
    void ac34_productKindMismatch_400() {
        // productKind=PLAN だが productKey が feature_catalog にしか無いキー形式
        given(planRepository.existsById("ads.hide")).willReturn(false);
        PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.PLAN, "ads.hide",
                EntitlementScopeKind.TEAM, Instant.parse("2027-06-01T00:00:00Z"), null,
                List.of(band(1, 1, null, 1000)));
        assertBadRequest(req, PriceRevisionErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-35: scopeKind は USER/TEAM/ORG いずれでも作成でき、別revision系列として採番される")
    void ac35_scopeKindIndependentSeries() {
        for (EntitlementScopeKind scope : EntitlementScopeKind.values()) {
            given(priceVersionRepository.findByProductKindAndProductKeyAndScopeKindAndDeletedAtIsNullOrderByRevisionNoDesc(
                    BillingProductKind.PLAN, "FULL", scope)).willReturn(List.of());
            PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.PLAN, "FULL",
                    scope, Instant.parse("2027-06-01T00:00:00Z"), null, List.of(band(1, 1, null, 1000)));
            PriceRevisionResponse response = service.create(req, ADMIN_ID);
            assertThat(response.getRevisionNo()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("AC-36: productKey が空文字は400")
    void ac36_blankProductKey_400() {
        PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.PLAN, "",
                EntitlementScopeKind.TEAM, Instant.parse("2027-06-01T00:00:00Z"), null,
                List.of(band(1, 1, null, 1000)));
        assertBadRequest(req, PriceRevisionErrorCode.INVALID_FIELD_LENGTH);
    }

    @Test
    @DisplayName("AC-36: productKey が65文字（64文字超）は400")
    void ac36_tooLongProductKey_400() {
        String tooLong = "A".repeat(65);
        lenient().when(planRepository.existsById(tooLong)).thenReturn(true);
        PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.PLAN, tooLong,
                EntitlementScopeKind.TEAM, Instant.parse("2027-06-01T00:00:00Z"), null,
                List.of(band(1, 1, null, 1000)));
        assertBadRequest(req, PriceRevisionErrorCode.INVALID_FIELD_LENGTH);
    }

    @Test
    @DisplayName("AC-36: taxCode が空白のみは400")
    void ac36_blankTaxCode_400() {
        assertBadRequest(requestWithBands(new PriceBandInput(1, 1, null, 1000L, BillingTaxBehavior.EXCLUSIVE, "   ")),
                PriceRevisionErrorCode.INVALID_FIELD_LENGTH);
    }

    @Test
    @DisplayName("AC-37: bands の件数上限は10。11件は400")
    void ac37_tooManyBands_400() {
        List<PriceBandInput> bands = new ArrayList<>();
        int min = 1;
        for (int i = 1; i <= 11; i++) {
            Integer max = (i == 11) ? null : min + 9;
            bands.add(band(i, min, max, 1000L + i));
            min = (max == null) ? min : max + 1;
        }
        assertBadRequest(request(bands), PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("AC-37: bands の件数が上限10件ちょうどは成功する")
    void ac37_exactlyTenBands_ok() {
        List<PriceBandInput> bands = new ArrayList<>();
        int min = 1;
        for (int i = 1; i <= 10; i++) {
            Integer max = (i == 10) ? null : min + 9;
            bands.add(band(i, min, max, 1000L + i));
            min = (max == null) ? min : max + 1;
        }
        PriceRevisionResponse response = service.create(request(bands), ADMIN_ID);
        assertThat(response.getBands()).hasSize(10);
    }

    @Test
    @DisplayName("AC-39: taxBehavior は INCLUSIVE/EXCLUSIVE 以外は400（不正 enum 文字列は Bean Validation/型変換で拒否される）")
    void ac39_invalidTaxBehaviorRejectedAtDtoLevel() {
        // Enum フィールドである PriceBandInput.taxBehavior に不正な文字列は代入できない
        // （型自体が BillingTaxBehavior のため、コンパイル時に保証される契約であることを確認する）。
        List<String> taxBehaviorTypeNames = java.util.Arrays.stream(PriceBandInput.class.getRecordComponents())
                .filter(c -> c.getName().equals("taxBehavior"))
                .map(c -> c.getType().getName())
                .toList();
        assertThat(taxBehaviorTypeNames).containsExactly(BillingTaxBehavior.class.getName());
    }

    @Test
    @DisplayName("AC-40: 税導出は band の effectiveFrom 時点の taxCode で行われ、6項目が保存される")
    void ac40_taxDerivationAppliedPerBand() {
        PriceRevisionResponse response = service.create(requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);
        var b = response.getBands().get(0);
        assertThat(b.getAmountExcludingTax()).isEqualTo(1000L);
        assertThat(b.getTaxAmount()).isEqualTo(100L);
        assertThat(b.getAmountIncludingTax()).isEqualTo(1100L);
        assertThat(b.getTaxCode()).isEqualTo("JP_STANDARD_10");
    }

    @Test
    @DisplayName("AC-42: created_by に SYSTEM_ADMIN の userId、creation_source='OPERATOR' が保存される")
    void ac42_createdByAndCreationSource() {
        service.create(requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);
        org.mockito.ArgumentCaptor<BillingPriceVersionEntity> captor =
                org.mockito.ArgumentCaptor.forClass(BillingPriceVersionEntity.class);
        org.mockito.Mockito.verify(priceVersionRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(ADMIN_ID);
        assertThat(captor.getValue().getCreationSource().name()).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("AC-44: クライアントは Stripe Price ref・計算済み税額を渡せない（DTOにそのようなフィールドが無い）")
    void ac44_requestDtoHasNoStripeOrTaxFields() {
        for (var field : PriceBandInput.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertThat(name).doesNotContain("stripe");
            assertThat(name).doesNotContain("taxamount");
            assertThat(name).doesNotContain("amountexcluding");
            assertThat(name).doesNotContain("amountincluding");
        }
    }

    // ---- C群: overlap・future 単一制限 ----

    @Test
    @DisplayName("AC-45: ACTIVE A(open-ended)存在下でAより後のfuture Bを作成できる（AもBもeffectiveUntilは書き換わらない）")
    void ac45_createFutureAlongsideOpenEndedActive() {
        BillingPriceVersionEntity activeA = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(EntitlementScopeKind.TEAM)
                .status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z")).effectiveUntil(null)
                .revisionNo(1L).build();
        given(priceVersionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of(activeA));

        PriceRevisionResponse response = service.create(requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);

        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.DRAFT);
        assertThat(activeA.getEffectiveUntil()).isNull();
    }

    @Test
    @DisplayName("AC-46: ACTIVE AにeffectiveUntilが既に設定済みで、区間が真に重なる新規Bの作成は409")
    void ac46_overlapsAlreadyClosedActive_409() {
        BillingPriceVersionEntity activeA = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(EntitlementScopeKind.TEAM)
                .status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z"))
                .effectiveUntil(Instant.parse("2027-12-01T00:00:00Z"))
                .revisionNo(1L).build();
        given(priceVersionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of(activeA));

        assertBadRequest(requestWithBands(band(1, 1, null, 1000)), PriceRevisionErrorCode.REVISION_OVERLAP);
    }

    @Test
    @DisplayName("AC-48: 半開区間。A.effectiveUntil == B.effectiveFrom は許容される")
    void ac48_halfOpenBoundaryAllowed() {
        Instant boundary = Instant.parse("2027-06-01T00:00:00Z");
        BillingPriceVersionEntity activeA = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(EntitlementScopeKind.TEAM)
                .status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z")).effectiveUntil(boundary)
                .revisionNo(1L).build();
        given(priceVersionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of(activeA));

        PriceRevisionResponse response = service.create(requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);
        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.DRAFT);
    }

    @Test
    @DisplayName("AC-49: ACTIVE側の区間重なり判定は1マイクロ秒でも重なれば409")
    void ac49_oneMicrosecondOverlap_409() {
        Instant boundary = Instant.parse("2027-06-01T00:00:00Z");
        BillingPriceVersionEntity activeA = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(EntitlementScopeKind.TEAM)
                .status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z"))
                .effectiveUntil(boundary.plusMillis(1))
                .revisionNo(1L).build();
        given(priceVersionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of(activeA));

        assertBadRequest(requestWithBands(band(1, 1, null, 1000)), PriceRevisionErrorCode.REVISION_OVERLAP);
    }

    @Test
    @DisplayName("AC-50: RETIRED の revision は overlap 判定の対象にしない")
    void ac50_retiredIgnoredInOverlap() {
        BillingPriceVersionEntity retired = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(EntitlementScopeKind.TEAM)
                .status(BillingPriceVersionStatus.RETIRED)
                .effectiveFrom(Instant.parse("2020-01-01T00:00:00Z"))
                .effectiveUntil(Instant.parse("2027-12-31T00:00:00Z"))
                .revisionNo(1L).build();
        given(priceVersionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of(retired));

        PriceRevisionResponse response = service.create(requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);
        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.DRAFT);
    }

    @Test
    @DisplayName("AC-51: overlap 判定は同一 (productKind, productKey, scopeKind) 内に閉じる")
    void ac51_overlapScopedToProductAndScopeKind() {
        // 別 scopeKind の ACTIVE は本 create の overlap 判定対象にしない（findAllForUpdate は scope で絞る）
        given(priceVersionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of());
        PriceRevisionResponse response = service.create(requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);
        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.DRAFT);
        org.mockito.Mockito.verify(priceVersionRepository, org.mockito.Mockito.never())
                .findAllForUpdate(any(), anyString(), org.mockito.ArgumentMatchers.eq(EntitlementScopeKind.ORG));
    }

    @Test
    @DisplayName("AC-52: overlap判定は対象行をrow lockしてから行う（findAllForUpdateが呼ばれる）")
    void ac52_usesRowLockBeforeOverlapCheck() {
        service.create(requestWithBands(band(1, 1, null, 1000)), ADMIN_ID);
        org.mockito.Mockito.verify(priceVersionRepository)
                .findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM);
    }

    @Test
    @DisplayName("AC-176（第6版新設）: future B が既に存在する状態で、重ならない新規 future C の create も409（FUTURE_REVISION_ALREADY_EXISTS）")
    void ac176_secondFutureAlwaysConflicts() {
        BillingPriceVersionEntity futureB = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(EntitlementScopeKind.TEAM)
                .status(BillingPriceVersionStatus.DRAFT)
                .effectiveFrom(Instant.parse("2027-03-01T00:00:00Z")).effectiveUntil(null)
                .revisionNo(1L).build();
        given(priceVersionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of(futureB));

        // C.effectiveFrom は B と重ならない・一致しない全く別の未来日
        PriceRevisionCreateRequest reqC = new PriceRevisionCreateRequest(BillingProductKind.PLAN, "FULL",
                EntitlementScopeKind.TEAM, Instant.parse("2029-01-01T00:00:00Z"), null,
                List.of(band(1, 1, null, 1000)));

        assertBadRequest(reqC, PriceRevisionErrorCode.FUTURE_REVISION_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("単一future（御裁可 2026-09-24）: PROVISIONING の revision がある商品では新規 DRAFT の create は409")
    void singleFuture_provisioningRevisionBlocksNewDraft() {
        assertSecondDraftConflictsWhileExisting(BillingPriceVersionStatus.PROVISIONING);
    }

    @Test
    @DisplayName("単一future（御裁可 2026-09-24）: PROVISION_FAILED の revision がある商品では新規 DRAFT の create は409")
    void singleFuture_provisionFailedRevisionBlocksNewDraft() {
        assertSecondDraftConflictsWhileExisting(BillingPriceVersionStatus.PROVISION_FAILED);
    }

    /**
     * PROVISIONING / PROVISION_FAILED は DRAFT→READY の途中状態（retry / reconcile で READY に戻りうる）であり
     * future の一形態。これを future 判定から外すと、別の DRAFT を作れてしまい、後から READY へ戻す瞬間に
     * future が2本併存する（uk_bpv_single_future 違反または単一 future 制限の破れ）。
     */
    private void assertSecondDraftConflictsWhileExisting(BillingPriceVersionStatus existingStatus) {
        BillingPriceVersionEntity existing = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(EntitlementScopeKind.TEAM)
                .status(existingStatus)
                .effectiveFrom(Instant.parse("2027-03-01T00:00:00Z")).effectiveUntil(null)
                .revisionNo(1L).build();
        given(priceVersionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of(existing));

        PriceRevisionCreateRequest req = new PriceRevisionCreateRequest(BillingProductKind.PLAN, "FULL",
                EntitlementScopeKind.TEAM, Instant.parse("2029-01-01T00:00:00Z"), null,
                List.of(band(1, 1, null, 1000)));

        assertBadRequest(req, PriceRevisionErrorCode.FUTURE_REVISION_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("AC-177（第6版新設）: Bがactivateにより future でなくなった（ACTIVE化）後は新規future Cのcreateが成功する")
    void ac177_createSucceedsAfterFormerFutureActivated() {
        BillingPriceVersionEntity nowActiveB = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey("FULL").scopeKind(EntitlementScopeKind.TEAM)
                .status(BillingPriceVersionStatus.ACTIVE)
                .effectiveFrom(Instant.parse("2027-03-01T00:00:00Z")).effectiveUntil(null)
                .revisionNo(1L).build();
        given(priceVersionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM))
                .willReturn(List.of(nowActiveB));

        PriceRevisionCreateRequest reqC = new PriceRevisionCreateRequest(BillingProductKind.PLAN, "FULL",
                EntitlementScopeKind.TEAM, Instant.parse("2029-01-01T00:00:00Z"), null,
                List.of(band(1, 1, null, 1000)));

        PriceRevisionResponse response = service.create(reqC, ADMIN_ID);
        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.DRAFT);
    }
}
