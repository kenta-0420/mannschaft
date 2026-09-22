package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceCreationSource;
import com.mannschaft.app.billing.BillingPriceProvisionGateway;
import com.mannschaft.app.billing.BillingPriceProvisionRecoveryService;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 試練隊（第2陣）F群: retry-provision と reconcile-provision（決定1・決定3改訂・決定9）。
 *
 * <p>陣立て書 F群（AC-89〜AC-104）の red 試練。対象は未実装の
 * {@link PriceRevisionRetryProvisionService} と決定3が命名した
 * {@link BillingPriceProvisionRecoveryService}（{@code BillingContractOperationRecoveryService} と
 * 同じドメインパッケージ {@code com.mannschaft.app.billing} に置く。本テストが発注書）。
 *
 * <h2>本群の核心（第5版・重大3対応を含む）</h2>
 * <ul>
 *   <li>retry対象は {@code status IN (DRAFT, PROVISION_FAILED)} の全band。READYのbandは触らない</li>
 *   <li>reconcileはmetadata一致だけでなく、unit_amount/currency/recurring/Product/tax_behavior/
 *       <b>Productのtax_code</b>まで全項目再照合する（決定3改訂・第5版重大3の直接反証）</li>
 *   <li>PROVISIONINGへのretryは409（回収はreconcile専用）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("試練F群: retry-provision / reconcile-provision")
class PriceRevisionRetryReconcileServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private BillingPriceVersionRepository versionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;
    @Mock private com.mannschaft.app.billing.BillingStripeProductRepository stripeProductRepository;
    @Mock private BillingPriceProvisionGateway gateway;

    private PriceRevisionRetryProvisionService retryService() {
        return new PriceRevisionRetryProvisionService(
                versionRepository, bandRepository, stripeProductRepository, gateway, FIXED_CLOCK);
    }

    private BillingPriceProvisionRecoveryService recoveryService() {
        return new BillingPriceProvisionRecoveryService(versionRepository, bandRepository, gateway, FIXED_CLOCK);
    }

    // ═════════ retry-provision ═════════

    @Test
    @DisplayName("AC-89: PROVISION_FAILEDのrevisionに対するretryは200で終局状態(READY/PROVISION_FAILED)を返す")
    void retryReturnsTerminalStateSynchronously() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISION_FAILED);
        BillingPriceBandVersionEntity failedBand = band(revision, 1, BillingPriceVersionStatus.PROVISION_FAILED);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(failedBand));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        given(gateway.createPrice(any())).willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_1"));

        PriceRevisionResponse response = retryService().retryProvision(revision.getId(), revision.getLockVersion());

        assertThat(response.getStatus())
                .isIn(BillingPriceVersionStatus.READY, BillingPriceVersionStatus.PROVISION_FAILED);
    }

    @Test
    @DisplayName("AC-90: retry対象はDRAFT/PROVISION_FAILEDの全band。READYのbandのPriceは作り直さない")
    void retryDoesNotRecreateReadyBands() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISION_FAILED);
        BillingPriceBandVersionEntity readyBand = band(revision, 1, BillingPriceVersionStatus.READY);
        readyBand.setStripePriceRef("price_already_ready");
        BillingPriceBandVersionEntity failedBand = band(revision, 2, BillingPriceVersionStatus.PROVISION_FAILED);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId()))
                .willReturn(List.of(readyBand, failedBand));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_1", true));
        given(gateway.createPrice(any())).willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_new"));

        retryService().retryProvision(revision.getId(), revision.getLockVersion());

        assertThat(readyBand.getStripePriceRef()).isEqualTo("price_already_ready");
        verify(gateway, times(1)).createPrice(any());
    }

    @Test
    @DisplayName("AC-91/AC-92: metadataで既存Priceを照合し、既に存在すれば再作成せず回収してREADYにする")
    void retryRecoversExistingPriceByMetadataInsteadOfRecreating() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISION_FAILED);
        BillingPriceBandVersionEntity failedBand = band(revision, 1, BillingPriceVersionStatus.PROVISION_FAILED);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(failedBand));
        given(gateway.findPriceByMetadata(revision.getId(), failedBand.getId()))
                .willReturn(Optional.of(matchingSnapshot(revision, failedBand, "price_recovered")));

        retryService().retryProvision(revision.getId(), revision.getLockVersion());

        assertThat(failedBand.getStripePriceRef()).isEqualTo("price_recovered");
        assertThat(failedBand.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        verify(gateway, never()).createPrice(any());
    }

    @Test
    @DisplayName("AC-93: 再試行のたびにband.provision_attemptsが増える")
    void retryIncrementsProvisionAttemptsEachTime() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISION_FAILED);
        BillingPriceBandVersionEntity failedBand = band(revision, 1, BillingPriceVersionStatus.PROVISION_FAILED);
        int before = failedBand.getProvisionAttempts();
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(failedBand));
        given(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(any(), any(), any()))
                .willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willThrow(new RuntimeException("still failing"));

        retryService().retryProvision(revision.getId(), revision.getLockVersion());

        assertThat(failedBand.getProvisionAttempts()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("AC-94: READY/SCHEDULED/ACTIVEに対するretry-provisionは409")
    void retryOnTerminalSuccessStateIsConflict() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.ACTIVE);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> retryService().retryProvision(revision.getId(), revision.getLockVersion()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("AC-95: status=PROVISIONINGのrevisionに対するretry-provisionは409（reconcile専用経路へ誘導）")
    void retryWhileProvisioningIsConflictAndMustUseReconcile() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISIONING);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> retryService().retryProvision(revision.getId(), revision.getLockVersion()))
                .isInstanceOf(BusinessException.class);
        verify(bandRepository, never()).findAllByPriceVersionIdForUpdate(any());
    }

    // ═════════ reconcile-provision ═════════

    @Test
    @DisplayName("AC-96: Stripe側の全属性(tax_code含む)がDB snapshotと一致する場合にのみREADYへ回収される")
    void reconcileRecoversOnlyWhenAllAttributesMatchIncludingTaxCode() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISIONING);
        BillingPriceBandVersionEntity stuckBand = band(revision, 1, BillingPriceVersionStatus.PROVISIONING);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(stuckBand));
        given(gateway.findPriceByMetadata(revision.getId(), stuckBand.getId()))
                .willReturn(Optional.of(matchingSnapshot(revision, stuckBand, "price_reconciled")));

        recoveryService().reconcileProvision(revision.getId(), revision.getLockVersion());

        assertThat(stuckBand.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(stuckBand.getStripePriceRef()).isEqualTo("price_reconciled");
    }

    @Test
    @DisplayName("AC-97: metadataは一致するがunit_amount等が異なるPriceはREADY化せずRECONCILE_ATTRIBUTE_MISMATCHで隔離する")
    void reconcileRejectsOnAttributeMismatch() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISIONING);
        BillingPriceBandVersionEntity stuckBand = band(revision, 1, BillingPriceVersionStatus.PROVISIONING);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(stuckBand));
        BillingPriceProvisionGateway.PriceSnapshot mismatched = new BillingPriceProvisionGateway.PriceSnapshot(
                "price_x", stuckBand.getInputAmount() + 1, "jpy", "month", 1,
                revision.getProductKind().name(), revision.getProductKey(), stuckBand.getTaxCodeSnapshot(),
                stuckBand.getTaxBehavior().name(), "test");
        given(gateway.findPriceByMetadata(revision.getId(), stuckBand.getId())).willReturn(Optional.of(mismatched));

        recoveryService().reconcileProvision(revision.getId(), revision.getLockVersion());

        assertThat(stuckBand.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);
        assertThat(stuckBand.getProvisionErrorCode()).isEqualTo("RECONCILE_ATTRIBUTE_MISMATCH");
    }

    @Test
    @DisplayName("AC-97a: metadata・unit_amount・currency・recurringは一致するがProductのtax_codeだけ異なる場合もREADY化せず隔離する")
    void reconcileRejectsWhenOnlyProductTaxCodeMismatches() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISIONING);
        BillingPriceBandVersionEntity stuckBand = band(revision, 1, BillingPriceVersionStatus.PROVISIONING);
        stuckBand.setTaxCodeSnapshot("JP_STANDARD_10");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(stuckBand));
        BillingPriceProvisionGateway.PriceSnapshot taxCodeMismatch = new BillingPriceProvisionGateway.PriceSnapshot(
                "price_x", stuckBand.getInputAmount(), "jpy", "month", 1,
                revision.getProductKind().name(), revision.getProductKey(), "JP_REDUCED_8",
                stuckBand.getTaxBehavior().name(), "test");
        given(gateway.findPriceByMetadata(revision.getId(), stuckBand.getId())).willReturn(Optional.of(taxCodeMismatch));

        recoveryService().reconcileProvision(revision.getId(), revision.getLockVersion());

        assertThat(stuckBand.getStatus())
                .as("Productのtax_codeだけがズレたPriceを誤って回収してはならない（第4版検分の重大3の直接反証）")
                .isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);
        assertThat(stuckBand.getProvisionErrorCode()).isEqualTo("RECONCILE_ATTRIBUTE_MISMATCH");
    }

    @Test
    @DisplayName("AC-98: Stripeに該当Priceが無い場合、reconcileはbandをPROVISION_FAILEDへ落とす")
    void reconcileFailsWhenNoMatchingPriceExistsInStripe() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISIONING);
        BillingPriceBandVersionEntity stuckBand = band(revision, 1, BillingPriceVersionStatus.PROVISIONING);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(stuckBand));
        given(gateway.findPriceByMetadata(revision.getId(), stuckBand.getId())).willReturn(Optional.empty());

        recoveryService().reconcileProvision(revision.getId(), revision.getLockVersion());

        assertThat(stuckBand.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);
    }

    @Test
    @DisplayName("AC-99/AC-100: staleThresholdは9分lease（第6版・重大3対応）と整合する（provision系3EPと同一の猶予）")
    void recoveryStaleThresholdMatchesNineMinuteLease() {
        assertThat(recoveryService().staleThreshold()).isEqualTo(Duration.ofMinutes(9));
    }

    @Test
    @DisplayName("AC-102: reconcileはSYSTEM_ADMIN限定。存在しないidは404")
    void reconcileUnknownIdThrowsNotFound() {
        UUID missing = UUID.randomUUID();
        given(versionRepository.findByIdAndDeletedAtIsNull(missing)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recoveryService().reconcileProvision(missing, 0L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.REVISION_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-103: reconcile-provisionはlockVersionを必須CAS条件とし、不一致は409")
    void reconcileRequiresLockVersionCas() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.PROVISIONING);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> recoveryService().reconcileProvision(revision.getId(), revision.getLockVersion() + 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
    }

    private static BillingPriceVersionEntity revision(BillingPriceVersionStatus status) {
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

    private static BillingPriceBandVersionEntity band(
            BillingPriceVersionEntity version, int bandNo, BillingPriceVersionStatus status) {
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
                .taxMasterSnapshot("{}")
                .amountExcludingTax(1000L)
                .taxAmount(100L)
                .taxRateBasisPoints(1000)
                .taxNameSnapshot("standard")
                .amountIncludingTax(1100L)
                .effectiveFrom(version.getEffectiveFrom())
                .status(status)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    private static BillingPriceProvisionGateway.PriceSnapshot matchingSnapshot(
            BillingPriceVersionEntity revision, BillingPriceBandVersionEntity band, String stripePriceId) {
        return new BillingPriceProvisionGateway.PriceSnapshot(
                stripePriceId, band.getInputAmount(), "jpy", "month", 1,
                revision.getProductKind().name(), revision.getProductKey(), band.getTaxCodeSnapshot(),
                band.getTaxBehavior().name(), "test");
    }
}
