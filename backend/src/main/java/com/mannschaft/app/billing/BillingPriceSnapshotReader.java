package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 昇格commit後のACTIVE親・bandを一つの読取りtransactionで取得する。 */
@Service
@RequiredArgsConstructor
public class BillingPriceSnapshotReader {

    private final BillingPriceVersionRepository priceVersionRepository;
    private final BillingPriceBandVersionRepository priceBandVersionRepository;

    @Transactional(readOnly = true)
    public Optional<Candidate> readActive(
            BillingProductKind productKind,
            String productKey,
            EntitlementScopeKind scopeKind,
            LocalDateTime at) {
        List<BillingPriceVersionEntity> active = priceVersionRepository.findEffectiveCandidates(
                productKind,
                productKey,
                scopeKind,
                List.of(BillingPriceVersionStatus.ACTIVE),
                at);
        if (active.size() != 1) {
            return Optional.empty();
        }
        BillingPriceVersionEntity revision = active.getFirst();
        List<BillingPriceBandVersionEntity> bands = priceBandVersionRepository
                .findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revision.getId());
        if (bands.isEmpty() || bands.stream().anyMatch(band ->
                band.getStatus() != BillingPriceVersionStatus.ACTIVE || !isEffectiveAt(band, at))) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(revision, List.copyOf(bands)));
    }

    private static boolean isEffectiveAt(BillingPriceBandVersionEntity band, LocalDateTime at) {
        return !band.getEffectiveFrom().isAfter(at)
                && (band.getEffectiveUntil() == null || at.isBefore(band.getEffectiveUntil()));
    }

    public record Candidate(
            BillingPriceVersionEntity revision,
            List<BillingPriceBandVersionEntity> bands) {
    }
}
