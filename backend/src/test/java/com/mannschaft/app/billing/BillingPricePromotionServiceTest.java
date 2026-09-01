package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("価格版の遅延昇格")
class BillingPricePromotionServiceTest {

    @Mock private BillingPriceVersionRepository versionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;

    @Test
    @DisplayName("開始済みSCHEDULEDを一度だけACTIVEへ昇格し、旧ACTIVEをRETIREDにする")
    void promotesDueRevisionAtomicallyAndIdempotently() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        BillingPriceVersionEntity current = version("current", BillingPriceVersionStatus.ACTIVE,
                Instant.parse("2026-08-01T00:00:00Z"), now);
        BillingPriceVersionEntity next = version("next", BillingPriceVersionStatus.SCHEDULED, now, null);
        BillingPriceBandVersionEntity currentBand = band(current, BillingPriceVersionStatus.ACTIVE);
        BillingPriceBandVersionEntity nextBand = band(next, BillingPriceVersionStatus.SCHEDULED);
        given(versionRepository.findEffectiveCandidates(BillingProductKind.PLAN, "FULL",
                EntitlementScopeKind.USER, List.of(BillingPriceVersionStatus.SCHEDULED), now))
                .willReturn(List.of(next), List.of());
        given(versionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER))
                .willReturn(List.of(current, next));
        given(bandRepository.findAllByPriceVersionIdForUpdate(next.getId())).willReturn(List.of(nextBand));
        given(bandRepository.findAllByPriceVersionIdForUpdate(current.getId())).willReturn(List.of(currentBand));
        BillingPricePromotionService service = new BillingPricePromotionService(versionRepository, bandRepository);

        assertThat(service.promoteDue(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now)).isTrue();
        assertThat(current.getStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED);
        assertThat(currentBand.getStatus()).isEqualTo(BillingPriceVersionStatus.RETIRED);
        assertThat(next.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);
        assertThat(nextBand.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);

        assertThat(service.promoteDue(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now)).isFalse();
    }

    @Test
    @DisplayName("due SCHEDULEDが重複している場合はどちらも昇格しない")
    void duplicateDueRevisionFailsClosed() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        BillingPriceVersionEntity first = version("first", BillingPriceVersionStatus.SCHEDULED, now, null);
        BillingPriceVersionEntity second = version("second", BillingPriceVersionStatus.SCHEDULED, now, null);
        given(versionRepository.findEffectiveCandidates(BillingProductKind.PLAN, "FULL",
                EntitlementScopeKind.USER, List.of(BillingPriceVersionStatus.SCHEDULED), now))
                .willReturn(List.of(first, second));
        given(versionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER))
                .willReturn(List.of(first, second));
        BillingPricePromotionService service = new BillingPricePromotionService(versionRepository, bandRepository);

        assertThat(service.promoteDue(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now)).isFalse();
        assertThat(first.getStatus()).isEqualTo(BillingPriceVersionStatus.SCHEDULED);
        assertThat(second.getStatus()).isEqualTo(BillingPriceVersionStatus.SCHEDULED);
        verify(bandRepository, never()).findAllByPriceVersionIdForUpdate(first.getId());
    }

    @Test
    @DisplayName("親価格版と有効期間が一致しないbandでは旧ACTIVEを退役させない")
    void inconsistentScheduledBandFailsClosedBeforeRetiringCurrent() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        BillingPriceVersionEntity current = version("current", BillingPriceVersionStatus.ACTIVE,
                Instant.parse("2026-08-01T00:00:00Z"), now);
        BillingPriceVersionEntity next = version("next", BillingPriceVersionStatus.SCHEDULED, now, null);
        BillingPriceBandVersionEntity inconsistentBand = band(next, BillingPriceVersionStatus.SCHEDULED);
        inconsistentBand.setEffectiveFrom(now.plusSeconds(1));
        given(versionRepository.findEffectiveCandidates(BillingProductKind.PLAN, "FULL",
                EntitlementScopeKind.USER, List.of(BillingPriceVersionStatus.SCHEDULED), now))
                .willReturn(List.of(next));
        given(versionRepository.findAllForUpdate(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER))
                .willReturn(List.of(current, next));
        given(bandRepository.findAllByPriceVersionIdForUpdate(next.getId()))
                .willReturn(List.of(inconsistentBand));
        BillingPricePromotionService service = new BillingPricePromotionService(versionRepository, bandRepository);

        assertThat(service.promoteDue(BillingProductKind.PLAN, "FULL", EntitlementScopeKind.USER, now)).isFalse();
        assertThat(current.getStatus()).isEqualTo(BillingPriceVersionStatus.ACTIVE);
        assertThat(next.getStatus()).isEqualTo(BillingPriceVersionStatus.SCHEDULED);
        verify(bandRepository, never()).findAllByPriceVersionIdForUpdate(current.getId());
    }

    private static BillingPriceVersionEntity version(
            String revision, BillingPriceVersionStatus status, Instant from, Instant until) {
        BillingPriceVersionEntity entity = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .productKey("FULL")
                .scopeKind(EntitlementScopeKind.USER)
                .catalogRevision(revision)
                .revisionNo((long) revision.hashCode() & 0x7fff_ffffL)
                .status(status)
                .effectiveFrom(from)
                .effectiveUntil(until)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    private static BillingPriceBandVersionEntity band(
            BillingPriceVersionEntity version, BillingPriceVersionStatus status) {
        BillingPriceBandVersionEntity entity = BillingPriceBandVersionEntity.builder()
                .productKind(version.getProductKind())
                .productKey(version.getProductKey())
                .scopeKind(version.getScopeKind())
                .priceVersionId(version.getId())
                .bandNo(1)
                .minMembers(1)
                .effectiveFrom(version.getEffectiveFrom())
                .effectiveUntil(version.getEffectiveUntil())
                .status(status)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
