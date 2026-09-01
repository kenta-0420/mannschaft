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

@ExtendWith(MockitoExtension.class)
@DisplayName("販売価格スナップショット読込")
class BillingPriceSnapshotReaderTest {

    @Mock private BillingPriceVersionRepository versionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;

    @Test
    @DisplayName("ACTIVE親の一部bandがACTIVEでなければ部分販売せず候補全体を落とす")
    void rejectsPartiallyActiveBands() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        BillingPriceVersionEntity revision = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .productKey("FULL")
                .scopeKind(EntitlementScopeKind.TEAM)
                .effectiveFrom(Instant.parse("2026-08-01T00:00:00Z"))
                .status(BillingPriceVersionStatus.ACTIVE)
                .build();
        revision.setId(UUID.randomUUID());
        BillingPriceBandVersionEntity activeBand = band(revision, 1, BillingPriceVersionStatus.ACTIVE, now);
        BillingPriceBandVersionEntity scheduledBand = band(revision, 2, BillingPriceVersionStatus.SCHEDULED, now);
        given(versionRepository.findEffectiveCandidates(
                BillingProductKind.PLAN,
                "FULL",
                EntitlementScopeKind.TEAM,
                List.of(BillingPriceVersionStatus.ACTIVE),
                now)).willReturn(List.of(revision));
        given(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revision.getId()))
                .willReturn(List.of(activeBand, scheduledBand));

        BillingPriceSnapshotReader reader = new BillingPriceSnapshotReader(versionRepository, bandRepository);

        assertThat(reader.readActive(
                BillingProductKind.PLAN, "FULL", EntitlementScopeKind.TEAM, now)).isEmpty();
    }

    private static BillingPriceBandVersionEntity band(
            BillingPriceVersionEntity revision,
            int bandNo,
            BillingPriceVersionStatus status,
            Instant now) {
        return BillingPriceBandVersionEntity.builder()
                .priceVersionId(revision.getId())
                .productKind(revision.getProductKind())
                .productKey(revision.getProductKey())
                .scopeKind(revision.getScopeKind())
                .bandNo(bandNo)
                .minMembers(bandNo)
                .effectiveFrom(Instant.parse("2026-08-01T00:00:00Z"))
                .status(status)
                .build();
    }
}
