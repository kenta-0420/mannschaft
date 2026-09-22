package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceCreationSource;
import com.mannschaft.app.billing.BillingPricePromotionService;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 試練隊（第2陣）G群: activate と B の遷移（第6版・future 単一制限で全面改訂）。
 *
 * <p>陣立て書 G群（AC-105〜AC-110・AC-114〜AC-119・AC-108a）の red 試練。対象は未実装の
 * {@link PriceRevisionActivationService}（本テストが発注書）。
 *
 * <h2>本群の核心</h2>
 * <ul>
 *   <li>全band READYのときだけactivate成功（AC-105）</li>
 *   <li>即時activateはactivate自身が同一トランザクションで旧ACTIVE→RETIRED・対象→ACTIVEを直接書き込む。
 *       {@code BillingPricePromotionService.promoteDue} は呼ばない（AC-108・AC-117）</li>
 *   <li>revision と配下の全band行が必ず同じ終局状態になる（AC-108a・重大4の直接反証）</li>
 *   <li>{@code BillingPricePromotionService} は無改修（決定4・注記5）。AC-117はコンストラクタ依存の不在で固定する
 *       （ArchUnit凍結ストアを汚染しない軽量な検体）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("試練G群: activate")
class PriceRevisionActivationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private BillingPriceVersionRepository versionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;

    private PriceRevisionActivationService service() {
        return new PriceRevisionActivationService(versionRepository, bandRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("AC-105: 全band READYのときだけactivateが成功する。1件でもREADYでなければ409")
    void activateFailsWhenAnyBandIsNotReady() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.READY);
        BillingPriceBandVersionEntity ready = band(revision, 1, BillingPriceVersionStatus.READY);
        BillingPriceBandVersionEntity notReady = band(revision, 2, BillingPriceVersionStatus.PROVISION_FAILED);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId()))
                .willReturn(List.of(ready, notReady));

        assertThatThrownBy(() -> service().activate(revision.getId(), revision.getLockVersion()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("AC-106: PROVISION_FAILED/PROVISIONING/DRAFTに対するactivateは409")
    void activateOnUnprovisionedStatesIsConflict() {
        for (BillingPriceVersionStatus status : List.of(
                BillingPriceVersionStatus.PROVISION_FAILED,
                BillingPriceVersionStatus.PROVISIONING,
                BillingPriceVersionStatus.DRAFT)) {
            BillingPriceVersionEntity revision = revision(status);
            given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

            assertThatThrownBy(() -> service().activate(revision.getId(), revision.getLockVersion()))
                    .as("status=" + status)
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("AC-107: effectiveFromが未来ならSCHEDULEDになる")
    void activateWithFutureEffectiveFromBecomesScheduled() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.READY);
        revision.setEffectiveFrom(NOW.plusSeconds(86_400));
        BillingPriceBandVersionEntity readyBand = band(revision, 1, BillingPriceVersionStatus.READY);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(readyBand));
        given(versionRepository.findAllForUpdate(
                revision.getProductKind(), revision.getProductKey(), revision.getScopeKind()))
                .willReturn(List.of(revision));

        service().activate(revision.getId(), revision.getLockVersion());

        assertThat(revision.getStatus()).isEqualTo(BillingPriceVersionStatus.SCHEDULED);
        assertThat(readyBand.getStatus()).isEqualTo(BillingPriceVersionStatus.SCHEDULED);
    }

    @Test
    @DisplayName("AC-108/AC-108a: 現在時刻ならactivate自身が同一Tx内で対象をACTIVE・旧ACTIVEをRETIREDに直接書き込み、revisionとbandが即座に一致した終局状態になる")
    void activateWithCurrentEffectiveFromWritesActiveDirectlyAndResolvableImmediately() {
        BillingPriceVersionEntity oldActive = revision(BillingPriceVersionStatus.ACTIVE);
        oldActive.setEffectiveFrom(NOW.minusSeconds(86_400));
        BillingPriceBandVersionEntity oldActiveBand = band(oldActive, 1, BillingPriceVersionStatus.ACTIVE);
        BillingPriceVersionEntity newRevision = revision(BillingPriceVersionStatus.READY);
        newRevision.setEffectiveFrom(NOW.minusSeconds(1));
        BillingPriceBandVersionEntity newReadyBand = band(newRevision, 1, BillingPriceVersionStatus.READY);
        given(versionRepository.findByIdAndDeletedAtIsNull(newRevision.getId())).willReturn(Optional.of(newRevision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(newRevision.getId())).willReturn(List.of(newReadyBand));
        given(versionRepository.findAllForUpdate(
                newRevision.getProductKind(), newRevision.getProductKey(), newRevision.getScopeKind()))
                .willReturn(List.of(oldActive, newRevision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(oldActive.getId())).willReturn(List.of(oldActiveBand));

        service().activate(newRevision.getId(), newRevision.getLockVersion());

        assertThat(newRevision.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);
        assertThat(newReadyBand.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);
        assertThat(oldActive.getStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED);
        assertThat(oldActiveBand.getStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED);
        // AC-108a: revisionとbandが同一状態・同一effectiveFromに揃っていることが resolveCurrentBand の
        // 完全一致条件（決定4）を満たすことの直接反証。
        assertThat(newRevision.getStatus()).isEqualTo(newReadyBand.getStatus());
        assertThat(newRevision.getEffectiveFrom()).isEqualTo(newReadyBand.getEffectiveFrom());
    }

    @Test
    @DisplayName("AC-109: Bのactivate(SCHEDULED化)と同一Txで既存ACTIVE Aのrevision/band両方のeffectiveUntilがB.effectiveFromに書き換わる")
    void activatingScheduledClosesPreviousActiveInSameTransaction() {
        BillingPriceVersionEntity activeA = revision(BillingPriceVersionStatus.ACTIVE);
        activeA.setEffectiveFrom(NOW.minusSeconds(86_400));
        BillingPriceBandVersionEntity activeABand = band(activeA, 1, BillingPriceVersionStatus.ACTIVE);
        BillingPriceVersionEntity futureB = revision(BillingPriceVersionStatus.READY);
        futureB.setEffectiveFrom(NOW.plusSeconds(86_400));
        BillingPriceBandVersionEntity futureBBand = band(futureB, 1, BillingPriceVersionStatus.READY);
        given(versionRepository.findByIdAndDeletedAtIsNull(futureB.getId())).willReturn(Optional.of(futureB));
        given(bandRepository.findAllByPriceVersionIdForUpdate(futureB.getId())).willReturn(List.of(futureBBand));
        given(versionRepository.findAllForUpdate(
                futureB.getProductKind(), futureB.getProductKey(), futureB.getScopeKind()))
                .willReturn(List.of(activeA, futureB));
        given(bandRepository.findAllByPriceVersionIdForUpdate(activeA.getId())).willReturn(List.of(activeABand));

        service().activate(futureB.getId(), futureB.getLockVersion());

        assertThat(activeA.getEffectiveUntil()).isEqualTo(futureB.getEffectiveFrom());
        assertThat(activeABand.getEffectiveUntil()).isEqualTo(futureB.getEffectiveFrom());
    }

    @Test
    @DisplayName("AC-110: Aが存在しない(初回投入)場合、Bのactivateは他revisionのeffectiveUntilを書き換えない")
    void activateWithoutExistingActiveDoesNotTouchOthers() {
        BillingPriceVersionEntity onlyRevision = revision(BillingPriceVersionStatus.READY);
        onlyRevision.setEffectiveFrom(NOW.plusSeconds(86_400));
        BillingPriceBandVersionEntity readyBand = band(onlyRevision, 1, BillingPriceVersionStatus.READY);
        given(versionRepository.findByIdAndDeletedAtIsNull(onlyRevision.getId())).willReturn(Optional.of(onlyRevision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(onlyRevision.getId())).willReturn(List.of(readyBand));
        given(versionRepository.findAllForUpdate(
                onlyRevision.getProductKind(), onlyRevision.getProductKey(), onlyRevision.getScopeKind()))
                .willReturn(List.of(onlyRevision));

        service().activate(onlyRevision.getId(), onlyRevision.getLockVersion());

        assertThat(onlyRevision.getEffectiveUntil()).isNull();
    }

    @Test
    @DisplayName("AC-115: lockVersion不一致は409（同時2本activateのCAS直接テスト）")
    void concurrentActivateOnlyOneSucceedsViaCas() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.READY);
        revision.setEffectiveFrom(NOW.plusSeconds(86_400));
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().activate(revision.getId(), revision.getLockVersion() + 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
    }

    @Test
    @DisplayName("AC-116: activate済みのrevisionに再度activateすると409")
    void reactivatingAlreadyActiveRevisionIsConflict() {
        BillingPriceVersionEntity revision = revision(BillingPriceVersionStatus.ACTIVE);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().activate(revision.getId(), revision.getLockVersion()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("AC-117: activate専用サービスはBillingPricePromotionServiceに依存しない（promoteDueを呼びようがない設計）")
    void activateNeverDependsOnPromotionService() throws NoSuchMethodException {
        Method activate = PriceRevisionActivationService.class.getMethod("activate", UUID.class, long.class);
        assertThat(activate).isNotNull();
        assertThat(PriceRevisionActivationService.class.getDeclaredConstructors())
                .noneMatch(constructor -> Arrays.stream(constructor.getParameterTypes())
                        .anyMatch(BillingPricePromotionService.class::equals))
                .as("即時activateとpromoteDueの責務分離（決定4）。BillingPricePromotionServiceは無改修のまま");
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
        boolean sellable = status != BillingPriceVersionStatus.DRAFT
                && status != BillingPriceVersionStatus.PROVISIONING
                && status != BillingPriceVersionStatus.PROVISION_FAILED;
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
                .effectiveUntil(version.getEffectiveUntil())
                .status(status)
                .stripePriceRef(sellable ? "price_" + UUID.randomUUID() : null)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
