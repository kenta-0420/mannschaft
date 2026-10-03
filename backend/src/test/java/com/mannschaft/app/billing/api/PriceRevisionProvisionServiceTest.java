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

import java.time.Instant;
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

    private PriceRevisionProvisionStateWriter stateWriter() {
        return new PriceRevisionProvisionStateWriter(versionRepository, bandRepository,
                org.mockito.Mockito.mock(com.mannschaft.app.auth.service.AuditLogService.class));
    }

    private PriceRevisionProvisionService service() {
        return new PriceRevisionProvisionService(stateWriter(), stripeProductRepository, gateway,
                new com.mannschaft.app.payment.stripe.StripeEnvironmentIdentifier());
    }

    private PriceRevisionRetryProvisionService retryService() {
        return new PriceRevisionRetryProvisionService(stateWriter(), stripeProductRepository, gateway,
                new com.mannschaft.app.payment.stripe.StripeEnvironmentIdentifier());
    }

    @Test
    @DisplayName("AC-66: 全band成功時は200と status='READY' を返す。202もPROVISIONINGも返さない")
    void allBandsSucceedReturnsReadySynchronously() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_10000000");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "txcd_10000000")).willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        PriceRevisionResponse response = service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(revision.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
    }

    @Test
    @DisplayName("AC-67: 全band失敗でも常に200と status='PROVISION_FAILED'（502分岐は無い）")
    void allBandsFailStillReturns200WithProvisionFailed() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_10000000");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "txcd_10000000")).willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willThrow(new RuntimeException("stripe unreachable"));

        PriceRevisionResponse response = service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);
    }

    @Test
    @DisplayName("AC-68（単体・呼び出し順序のみ）: Stripe 呼び出しの時点で band は PROVISIONING に遷移済み。"
            + "commit 済みであることの実証は PriceRevisionProvisionTransactionIT が実 DB で行う")
    void provisioningIsPersistedBeforeStripeIsCalled() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_10000000");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "txcd_10000000")).willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willAnswer(invocation -> {
            assertThat(b1.getStatus())
                    .as("Stripe呼び出し時点で band は PROVISIONING に遷移済みでなければならない")
                    .isEqualTo(BillingPriceVersionStatus.PROVISIONING);
            return new BillingPriceProvisionGateway.ProductResolution("prod_1", true);
        });
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);
    }

    @Test
    @DisplayName("AC-101（ABA）: provision の Stripe 呼び出し中に revision が他操作で更新されたら complete は409で結果を反映しない")
    void provisionCompleteRejectsWhenRevisionChangedDuringStripeCall() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_10000000");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        long lockVersionAtRequest = revision.getLockVersion();
        given(gateway.createPrice(any())).willAnswer(invocation -> {
            // begin の commit 後、Stripe 呼び出し中に別経路が revision を更新して lockVersion が進んだ状況。
            revision.setLockVersion(revision.getLockVersion() + 1);
            return new BillingPriceProvisionGateway.PriceCreationResult("price_raced");
        });

        assertThatThrownBy(() -> service().provision(revision.getId(), lockVersionAtRequest, 700_001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        assertThat(b1.getStripePriceRef()).as("競合時は結果を反映しない").isNull();
        assertThat(b1.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISIONING);
    }

    @Test
    @DisplayName("旧形式 snapshot（stripeTaxCode キー欠落＝修正前に作られた band）は fail-closed:"
            + " Stripe を呼ばず PROVISION_FAILED＋TAX_SNAPSHOT_LEGACY_FORMAT（黙って無課税の Product を作らない）")
    void legacyTaxSnapshotFailsClosedWithoutCallingStripe() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_99999999");
        b1.setTaxMasterSnapshot("{\"code\":\"JP_STANDARD_10\",\"displayName\":\"standard\",\"rateBasisPoints\":1000}");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));

        PriceRevisionResponse response = service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);
        assertThat(b1.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);
        assertThat(b1.getProvisionErrorCode()).isEqualTo("TAX_SNAPSHOT_LEGACY_FORMAT");
        verify(gateway, never()).findPriceByMetadata(any(), any());
        verify(gateway, never()).resolveOrCreateProduct(any());
        verify(gateway, never()).createPrice(any());
    }

    @Test
    @DisplayName("対照: stripeTaxCode キーはあるが値が null（マスタに Stripe 用コードが無い）は未設定として扱い、"
            + "tax_code 無しで Product を解決する（AC-85）")
    void explicitNullStripeTaxCodeIsTreatedAsUnset() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_99999999");
        b1.setTaxMasterSnapshot("{\"code\":\"JP_STANDARD_10\",\"displayName\":\"standard\","
                + "\"rateBasisPoints\":1000,\"stripeTaxCode\":null}");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), org.mockito.ArgumentMatchers.isNull()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_notax", true));
        given(gateway.createPrice(any())).willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_1"));
        ArgumentCaptor<BillingPriceProvisionGateway.ProductResolutionCommand> captor =
                ArgumentCaptor.forClass(BillingPriceProvisionGateway.ProductResolutionCommand.class);

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        verify(gateway).resolveOrCreateProduct(captor.capture());
        assertThat(captor.getValue().stripeTaxCode()).isNull();
        assertThat(b1.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
    }

    @Test
    @DisplayName("AC-72: band5件中3件目が失敗しても4・5件目を試行する（fail-forward）。結果は1,2,4,5がREADY、3がPROVISION_FAILED")
    void failForwardContinuesRemainingBandsAfterOneFailure() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        List<BillingPriceBandVersionEntity> bands = List.of(
                band(revision, 1, "txcd_10000000"), band(revision, 2, "txcd_10000000"), band(revision, 3, "txcd_10000000"),
                band(revision, 4, "txcd_10000000"), band(revision, 5, "txcd_10000000"));
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

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

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
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_10000000");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willThrow(new RuntimeException("boom"));

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        assertThat(b1.getProvisionErrorCode()).isNotBlank();
        assertThat(revision.getLastProvisionErrorCode()).isNotBlank();
        assertThat(revision.getProvisionAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-74/AC-75: band ごとにstripeTaxCodeが異なれば別々のProductが解決され、既存Productのtax_codeは上書きされない")
    void differentTaxCodesResolveDistinctProductsWithoutOverwritingExisting() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity standard = band(revision, 1, "txcd_99999999");
        BillingPriceBandVersionEntity reduced = band(revision, 2, "txcd_20030000");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId()))
                .willReturn(List.of(standard, reduced));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "txcd_99999999"))
                .willReturn(Optional.empty());
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "txcd_20030000"))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any()))
                .willReturn(new BillingPriceProvisionGateway.ProductResolution("prod_standard", true))
                .willReturn(new BillingPriceProvisionGateway.ProductResolution("prod_reduced", true));
        given(gateway.createPrice(any()))
                .willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_standard"))
                .willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_reduced"));
        ArgumentCaptor<BillingPriceProvisionGateway.ProductResolutionCommand> captor =
                ArgumentCaptor.forClass(BillingPriceProvisionGateway.ProductResolutionCommand.class);

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        verify(gateway, times(2)).resolveOrCreateProduct(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(BillingPriceProvisionGateway.ProductResolutionCommand::stripeTaxCode)
                .containsExactlyInAnyOrder("txcd_99999999", "txcd_20030000");
    }

    @Test
    @DisplayName("AC-76: lockVersion不一致は409相当のCAS例外")
    void lockVersionMismatchThrowsConflict() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().provision(revision.getId(), revision.getLockVersion() + 999, 700_001L))
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

        assertThatThrownBy(() -> service().provision(revision.getId(), revision.getLockVersion(), 700_001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("AC-78: PROVISIONING中（回収待ち）のrevisionへのprovisionは409")
    void provisionWhileAlreadyProvisioningIsConflict() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISIONING);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().provision(revision.getId(), revision.getLockVersion(), 700_001L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-79/AC-80/AC-81/AC-82: 作成するPriceのmetadata・unit_amount・currency・recurring・tax_behaviorがband snapshotと一致する")
    void createdPriceMatchesBandSnapshotAttributes() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_99999999");
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

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

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
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_99999999");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        BillingStripeProductEntity existingMapping = new BillingStripeProductEntity(
                revision.getProductKind(), revision.getProductKey(), "txcd_99999999", "prod_existing");
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "txcd_99999999"))
                .willReturn(Optional.of(existingMapping));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        verify(gateway, never()).resolveOrCreateProduct(any());
    }

    @Test
    @DisplayName("AC-86: 決定10のIdempotency-Keyがgatewayコマンドに渡る（price-band-create:{bandId}）")
    void idempotencyKeysAreDeterministic() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_99999999");
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

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        verify(gateway).createPrice(priceCaptor.capture());
        assertThat(priceCaptor.getValue().idempotencyKey()).isEqualTo("price-band-create:" + b1.getId());
    }

    @Test
    @DisplayName("AC-88a: 初回ProvisionでProduct新規作成された場合、billing_stripe_productsに1件永続化される")
    void newProductResolutionPersistsMappingRow() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_99999999");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_new", true));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));
        ArgumentCaptor<BillingStripeProductEntity> mappingCaptor = ArgumentCaptor.forClass(BillingStripeProductEntity.class);

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        verify(stripeProductRepository).save(mappingCaptor.capture());
        assertThat(mappingCaptor.getValue().getStripeProductId()).isEqualTo("prod_new");
        assertThat(mappingCaptor.getValue().getStripeTaxCode()).isEqualTo("txcd_99999999");
    }

    @Test
    @DisplayName("AC-88b: DBマッピング先読みヒット時、retry-provision相当の呼び出しでProduct系Stripe API呼び出し回数は0")
    void dbMappingHitMakesZeroProductApiCalls() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISION_FAILED);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_99999999");
        b1.setStatus(BillingPriceVersionStatus.PROVISION_FAILED);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                revision.getProductKind(), revision.getProductKey(), "txcd_99999999"))
                .willReturn(Optional.of(new BillingStripeProductEntity(
                        revision.getProductKind(), revision.getProductKey(), "txcd_99999999", "prod_cached")));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        retryService()
                .retryProvision(revision.getId(), revision.getLockVersion(), 700_001L);

        verify(gateway, never()).resolveOrCreateProduct(any());
        verify(gateway, times(1)).createPrice(any());
    }

    @Test
    @DisplayName("AC-88d: band10件全てで作成競合が発生する最悪ケースでもStripe呼び出し総数30回で完了する")
    void worstCaseThirtyStripeCallsCompletesWithinBudget() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        List<BillingPriceBandVersionEntity> bands = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            bands.add(band(revision, i, "txcd_1000000" + i));
        }
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(bands);
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any()))
                .willReturn(new BillingPriceProvisionGateway.ProductResolution("prod_conflict", false));
        given(gateway.createPrice(any())).willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_x"));

        long startNanos = System.nanoTime();
        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);
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
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_99999999");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_won_by_other", false));
        given(gateway.createPrice(any())).willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        service().provision(revision.getId(), revision.getLockVersion(), 700_001L);

        verify(stripeProductRepository).save(any());
        assertThat(b1.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
    }

    @Test
    @DisplayName("AC-135: 同一revisionへprovisionを2回実行してもcreatePriceは1回だけ"
            + "（2回目は状態が既にREADYのため409で本処理へ到達しない＝Stripe Price二重作成を防止）")
    void provisioningTwiceDoesNotCreatePriceTwice() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.DRAFT);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_10000000");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        PriceRevisionProvisionService service = service();
        PriceRevisionResponse first = service.provision(revision.getId(), revision.getLockVersion(), 700_001L);
        assertThat(first.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);

        // 実運用ではIdempotency-Keyの再送で同じ操作が2回来るが、1回目でrevisionはREADYへ遷移済みのため
        // 2回目は状態競合(409)で弾かれ、gateway.createPriceへは一度も到達しない。
        assertThatThrownBy(() -> service.provision(revision.getId(), revision.getLockVersion(), 700_001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.STATE_CONFLICT);

        verify(gateway, times(1)).createPrice(any());
    }

    @Test
    @DisplayName("AC-135: retry-provisionを2回実行してもcreatePriceは1回だけ"
            + "（1回目でrevisionはREADYへ遷移し、2回目はrevision状態がPROVISION_FAILEDでないため"
            + "409で本処理へ到達しない＝Stripe Price二重作成を防止）")
    void retryProvisionTwiceDoesNotRecreatePriceForAlreadyReadyBand() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISION_FAILED);
        BillingPriceBandVersionEntity b1 = band(revision, 1, "txcd_10000000");
        b1.setStatus(BillingPriceVersionStatus.PROVISION_FAILED);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        given(gateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        PriceRevisionRetryProvisionService retryService = retryService();

        PriceRevisionResponse first = retryService.retryProvision(revision.getId(), revision.getLockVersion(), 700_001L);
        assertThat(first.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(b1.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);

        // 2回目のretry: revisionは既にREADY（PROVISION_FAILEDでない）ため状態競合(409)で弾かれ、
        // gateway.createPriceへは一度も到達しない。
        assertThatThrownBy(() -> retryService.retryProvision(revision.getId(), revision.getLockVersion(), 700_001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.STATE_CONFLICT);

        verify(gateway, times(1)).createPrice(any());
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

    /**
     * @param stripeTaxCode band の {@code tax_master_snapshot} に固定する Stripe 側税コード。
     *                      内部税コード（{@code taxCodeSnapshot}）は常に {@code JP_STANDARD_10} とし、
     *                      Stripe へ渡るのが snapshot の Stripe 側税コードであることを区別できるようにする
     *                      （Codex 検分 P1: 以前は内部 code を Stripe 税コードとして扱う fixture が欠陥を追認していた）。
     */
    static BillingPriceBandVersionEntity band(BillingPriceVersionEntity version, int bandNo, String stripeTaxCode) {
        BillingPriceBandVersionEntity entity = BillingPriceBandVersionEntity.builder()
                .productKind(version.getProductKind())
                .productKey(version.getProductKey())
                .scopeKind(version.getScopeKind())
                .priceVersionId(version.getId())
                .bandNo(bandNo)
                .minMembers(1)
                .inputAmount(1000L)
                .taxBehavior(BillingTaxBehavior.EXCLUSIVE)
                .taxCodeSnapshot("JP_STANDARD_10")
                .taxMasterSnapshot("{\"code\":\"JP_STANDARD_10\",\"displayName\":\"standard\","
                        + "\"rateBasisPoints\":1000,\"stripeTaxCode\":\"" + stripeTaxCode + "\"}")
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
