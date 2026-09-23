package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceCreationSource;
import com.mannschaft.app.billing.BillingPriceProvisionGateway;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingStripeProductEntity;
import com.mannschaft.app.billing.BillingStripeProductRepository;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 試練隊（第2陣）E群: 同期 Provision（{@code POST /price-revisions/{id}/provision}）。
 *
 * <p>陣立て書 E群（AC-66〜AC-88d）の red 試練。対象は未実装の {@link PriceRevisionProvisionService} と
 * 新設 Gateway ポート {@link BillingPriceProvisionGateway}（本テストが発注書。{@code BillingPlanChangeGateway}
 * と同じ流儀でドメインパッケージ {@code com.mannschaft.app.billing} に置く）。
 *
 * <h2>決定9改訂の要点（本テストが固定する契約）</h2>
 * <ul>
 *   <li>Product 解決キーは {@code productKind+productKey+stripeTaxCode}。DB
 *       （{@link BillingStripeProductRepository}）を先読みし、ヒットすれば Stripe を一切呼ばない</li>
 *   <li>ミス時は決定的 Product ID で {@code create} を1回。競合時のみ {@code retrieve} を追加</li>
 *   <li>fail-forward: 1 band の失敗で以降を中断しない</li>
 *   <li>応答は常に200＋終局状態（READY/PROVISION_FAILED）。202は返さない（決定2）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("試練E群: 同期Provision")
class PriceRevisionProvisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @Mock private BillingPriceVersionRepository versionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;
    @Mock private BillingStripeProductRepository stripeProductRepository;
    @Mock private BillingPriceProvisionGateway gateway;

    private PriceRevisionProvisionService service() {
        return new PriceRevisionProvisionService(
                versionRepository, bandRepository, stripeProductRepository, gateway,
                Clock.fixed(NOW, ZoneOffset.UTC), new com.mannschaft.app.payment.stripe.StripeEnvironmentIdentifier());
    }

    @Test
    @DisplayName("AC-66: 全band成功時は200と status='READY' を返す。202もPROVISIONINGも返さない")
    void allBandsSucceedReturnsReadySynchronously() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "TAX10");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "TAX10")).willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        PriceRevisionResponse response = service().provision(revision.getId(), revision.getLockVersion());

        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(revision.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
    }

    @Test
    @DisplayName("AC-67: 全band失敗でも常に200と status='PROVISION_FAILED'（502分岐は無い）")
    void allBandsFailStillReturns200WithProvisionFailed() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "TAX10");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "TAX10")).willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willThrow(new RuntimeException("stripe unreachable"));

        PriceRevisionResponse response = service().provision(revision.getId(), revision.getLockVersion());

        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);
    }

    @Test
    @DisplayName("AC-68: DBにbandごとPROVISIONINGをcommitした後にのみStripeを呼ぶ（呼び出し順序）")
    void provisioningIsPersistedBeforeStripeIsCalled() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "TAX10");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "TAX10")).willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willAnswer(invocation -> {
            assertThat(b1.getStatus())
                    .as("Stripe呼び出し時点でDBはPROVISIONINGへ既にcommitされていなければならない")
                    .isEqualTo(BillingPriceVersionStatus.PROVISIONING);
            return new BillingPriceProvisionGateway.ProductResolution("prod_1", true);
        });
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        service().provision(revision.getId(), revision.getLockVersion());
    }

    @Test
    @DisplayName("AC-72: band5件中3件目が失敗しても4・5件目を試行する（fail-forward）。結果は1,2,4,5がREADY、3がPROVISION_FAILED")
    void failForwardContinuesRemainingBandsAfterOneFailure() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        List<BillingPriceBandVersionEntity> bands = List.of(
                band(revision, 1, "TAX10"), band(revision, 2, "TAX10"), band(revision, 3, "TAX10"),
                band(revision, 4, "TAX10"), band(revision, 5, "TAX10"));
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(bands);
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        given(gateway.createPrice(any()))
                .willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_1"))
                .willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_2"))
                .willThrow(new RuntimeException("band3 stripe failure"))
                .willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_4"))
                .willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_5"));

        service().provision(revision.getId(), revision.getLockVersion());

        assertThat(bands.get(0).getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(bands.get(1).getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(bands.get(2).getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);
        assertThat(bands.get(3).getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(bands.get(4).getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(revision.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);
        verify(gateway, times(5)).createPrice(any());
    }

    @Test
    @DisplayName("AC-73: 失敗bandにprovision_error_code/provision_attempts=1、親にlast_provision_error_code/provision_attemptsが保存される")
    void failedBandRecordsErrorCodeAndAttempts() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "TAX10");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willThrow(new RuntimeException("boom"));

        service().provision(revision.getId(), revision.getLockVersion());

        assertThat(b1.getProvisionErrorCode()).isNotBlank();
        assertThat(revision.getLastProvisionErrorCode()).isNotBlank();
        assertThat(revision.getProvisionAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-74/AC-75: band ごとにstripeTaxCodeが異なれば別々のProductが解決され、既存Productのtax_codeは上書きされない")
    void differentTaxCodesResolveDistinctProductsWithoutOverwritingExisting() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity standard = band(revision, 1, "JP_STANDARD_10");
        BillingPriceBandVersionEntity reduced = band(revision, 2, "JP_REDUCED_8");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId()))
                .willReturn(List.of(standard, reduced));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "JP_STANDARD_10"))
                .willReturn(Optional.empty());
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "JP_REDUCED_8"))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any()))
                .willReturn(new BillingPriceProvisionGateway.ProductResolution("prod_standard", true))
                .willReturn(new BillingPriceProvisionGateway.ProductResolution("prod_reduced", true));
        given(gateway.createPrice(any()))
                .willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_standard"))
                .willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_reduced"));
        ArgumentCaptor<BillingPriceProvisionGateway.ProductResolutionCommand> captor =
                ArgumentCaptor.forClass(BillingPriceProvisionGateway.ProductResolutionCommand.class);

        service().provision(revision.getId(), revision.getLockVersion());

        verify(gateway, times(2)).resolveOrCreateProduct(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(BillingPriceProvisionGateway.ProductResolutionCommand::stripeTaxCode)
                .containsExactlyInAnyOrder("JP_STANDARD_10", "JP_REDUCED_8");
    }

    @Test
    @DisplayName("AC-76: lockVersion不一致は409相当のCAS例外")
    void lockVersionMismatchThrowsConflict() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().provision(revision.getId(), revision.getLockVersion() + 999))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        verify(bandRepository, never()).findAllByPriceVersionIdForUpdate(any());
    }

    @Test
    @DisplayName("AC-77: 既にREADY/SCHEDULED/ACTIVE/RETIREDのrevisionへのprovisionは409")
    void provisionOnAlreadyProvisionedRevisionIsConflict() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.READY);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().provision(revision.getId(), revision.getLockVersion()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("AC-78: PROVISIONING中（回収待ち）のrevisionへのprovisionは409")
    void provisionWhileAlreadyProvisioningIsConflict() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISIONING);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().provision(revision.getId(), revision.getLockVersion()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-79/AC-80/AC-81/AC-82: 作成するPriceのmetadata・unit_amount・currency・recurring・tax_behaviorがband snapshotと一致する")
    void createdPriceMatchesBandSnapshotAttributes() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "JP_STANDARD_10");
        b1.setInputAmount(1200L);
        b1.setTaxBehavior(BillingTaxBehavior.EXCLUSIVE);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));
        ArgumentCaptor<BillingPriceProvisionGateway.PriceCreationCommand> captor =
                ArgumentCaptor.forClass(BillingPriceProvisionGateway.PriceCreationCommand.class);

        service().provision(revision.getId(), revision.getLockVersion());

        verify(gateway).createPrice(captor.capture());
        BillingPriceProvisionGateway.PriceCreationCommand command = captor.getValue();
        assertThat(command.unitAmount()).isEqualTo(1200L);
        assertThat(command.currency()).isEqualTo("jpy");
        assertThat(command.recurringInterval()).isEqualTo("month");
        assertThat(command.recurringIntervalCount()).isEqualTo(1);
        assertThat(command.taxBehavior()).isEqualTo("EXCLUSIVE");
        assertThat(command.metadata()).containsEntry("revisionId", revision.getId().toString());
        assertThat(command.metadata()).containsEntry("bandId", b1.getId().toString());
    }

    @Test
    @DisplayName("AC-83/AC-84: DBマッピングにヒットした既存Productはtax_codeを再確認・上書きせず、Stripe Product系APIを呼ばない")
    void existingProductMappingSkipsStripeProductCall() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "JP_STANDARD_10");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        BillingStripeProductEntity existingMapping = new BillingStripeProductEntity(
                revision.getProductKind(), revision.getProductKey(), "JP_STANDARD_10", "prod_existing");
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "JP_STANDARD_10"))
                .willReturn(Optional.of(existingMapping));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        service().provision(revision.getId(), revision.getLockVersion());

        verify(gateway, never()).resolveOrCreateProduct(any());
    }

    @Test
    @DisplayName("AC-86: 決定10のIdempotency-Keyがgatewayコマンドに渡る（price-band-create:{bandId}）")
    void idempotencyKeysAreDeterministic() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "JP_STANDARD_10");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));
        ArgumentCaptor<BillingPriceProvisionGateway.PriceCreationCommand> priceCaptor =
                ArgumentCaptor.forClass(BillingPriceProvisionGateway.PriceCreationCommand.class);

        service().provision(revision.getId(), revision.getLockVersion());

        verify(gateway).createPrice(priceCaptor.capture());
        assertThat(priceCaptor.getValue().idempotencyKey()).isEqualTo("price-band-create:" + b1.getId());
    }

    @Test
    @DisplayName("AC-88a: 初回ProvisionでProduct新規作成された場合、billing_stripe_productsに1件永続化される")
    void newProductResolutionPersistsMappingRow() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "JP_STANDARD_10");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_new", true));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));
        ArgumentCaptor<BillingStripeProductEntity> mappingCaptor = ArgumentCaptor.forClass(BillingStripeProductEntity.class);

        service().provision(revision.getId(), revision.getLockVersion());

        verify(stripeProductRepository).save(mappingCaptor.capture());
        assertThat(mappingCaptor.getValue().getStripeProductId()).isEqualTo("prod_new");
        assertThat(mappingCaptor.getValue().getStripeTaxCode()).isEqualTo("JP_STANDARD_10");
    }

    @Test
    @DisplayName("AC-88b: DBマッピング先読みヒット時、retry-provision相当の呼び出しでProduct系Stripe API呼び出し回数は0")
    void dbMappingHitMakesZeroProductApiCalls() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISION_FAILED);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "JP_STANDARD_10");
        b1.setStatus(BillingPriceVersionStatus.PROVISION_FAILED);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "JP_STANDARD_10"))
                .willReturn(Optional.of(new BillingStripeProductEntity(
                        revision.getProductKind(), revision.getProductKey(), "JP_STANDARD_10", "prod_cached")));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        new PriceRevisionRetryProvisionService(
                versionRepository, bandRepository, stripeProductRepository, gateway,
                Clock.fixed(NOW, ZoneOffset.UTC), new com.mannschaft.app.payment.stripe.StripeEnvironmentIdentifier())
                .retryProvision(revision.getId(), revision.getLockVersion());

        verify(gateway, never()).resolveOrCreateProduct(any());
        verify(gateway, times(1)).createPrice(any());
    }

    @Test
    @DisplayName("AC-88d: band10件全てで作成競合が発生する最悪ケースでもStripe呼び出し総数30回で完了する")
    void worstCaseThirtyStripeCallsCompletesWithinBudget() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        List<BillingPriceBandVersionEntity> bands = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            bands.add(band(revision, i, "TAXCODE_" + i));
        }
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(bands);
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any()))
                .willReturn(new BillingPriceProvisionGateway.ProductResolution("prod_conflict", false));
        given(gateway.createPrice(any())).willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_x"));

        long startNanos = System.nanoTime();
        service().provision(revision.getId(), revision.getLockVersion());
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        verify(gateway, times(10)).resolveOrCreateProduct(any());
        verify(gateway, times(10)).createPrice(any());
        // 単体テストではStripe通信はmockなので実測時間は無意味だが、
        // 実装がハングしていないことを緩い上限で確認する（本番の9分(540秒)lease算定は決定9の理論値で保証）。
        assertThat(elapsedMillis).isLessThan(60_000L);
    }

    @Test
    @DisplayName("AC-88c: 同一の新規(productKind,productKey,stripeTaxCode)への同時解決は決定的Product IDの重複を経てretrieveへ収束する")
    void concurrentFirstResolutionConvergesViaRetrieve() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "JP_STANDARD_10");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_won_by_other", false));
        given(gateway.createPrice(any())).willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        service().provision(revision.getId(), revision.getLockVersion());

        verify(stripeProductRepository).save(any());
        assertThat(b1.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
    }

    static BillingPriceVersionEntity revision(BillingPriceVersionStatus status) {
        BillingPriceVersionEntity entity = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .productKey("FULL")
                .scopeKind(EntitlementScopeKind.USER)
                .catalogRevision("rev-" + UUID.randomUUID())
                .revisionNo(1L)
                .status(status)
                .effectiveFrom(NOW.plusSeconds(3600))
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    static BillingPriceBandVersionEntity band(BillingPriceVersionEntity version, int bandNo, String taxCode) {
        BillingPriceBandVersionEntity entity = BillingPriceBandVersionEntity.builder()
                .productKind(version.getProductKind())
                .productKey(version.getProductKey())
                .scopeKind(version.getScopeKind())
                .priceVersionId(version.getId())
                .bandNo(bandNo)
                .minMembers(1)
                .inputAmount(1000L)
                .taxBehavior(BillingTaxBehavior.EXCLUSIVE)
                .taxCodeSnapshot(taxCode)
                .taxMasterSnapshot("{}")
                .amountExcludingTax(1000L)
                .taxAmount(100L)
                .taxRateBasisPoints(1000)
                .taxNameSnapshot("standard")
                .amountIncludingTax(1100L)
                .effectiveFrom(version.getEffectiveFrom())
                .status(BillingPriceVersionStatus.DRAFT)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
