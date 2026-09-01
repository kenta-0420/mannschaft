package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 開始時刻を迎えた価格版を、公開参照に先立って原子的に昇格する。 */
@Service
@RequiredArgsConstructor
public class BillingPricePromotionService {

    private final BillingPriceVersionRepository priceVersionRepository;
    private final BillingPriceBandVersionRepository priceBandVersionRepository;

    @Transactional
    public boolean promoteDue(
            BillingProductKind productKind,
            String productKey,
            EntitlementScopeKind scopeKind,
            LocalDateTime now) {
        if (priceVersionRepository.findEffectiveCandidates(
                productKind,
                productKey,
                scopeKind,
                List.of(BillingPriceVersionStatus.SCHEDULED),
                now).isEmpty()) {
            return false;
        }

        List<BillingPriceVersionEntity> revisions =
                priceVersionRepository.findAllForUpdate(productKind, productKey, scopeKind);
        List<BillingPriceVersionEntity> due = revisions.stream()
                .filter(revision -> revision.getStatus() == BillingPriceVersionStatus.SCHEDULED)
                .filter(revision -> isEffectiveAt(revision, now))
                .toList();
        if (due.isEmpty()) {
            return false;
        }

        List<BillingPriceVersionEntity> active = revisions.stream()
                .filter(revision -> revision.getStatus() == BillingPriceVersionStatus.ACTIVE)
                .toList();
        if (due.size() != 1 || active.size() > 1) {
            return false;
        }

        BillingPriceVersionEntity next = due.getFirst();
        List<BillingPriceBandVersionEntity> nextBands =
                priceBandVersionRepository.findAllByPriceVersionIdForUpdate(next.getId());
        if (nextBands.isEmpty()
                || nextBands.stream().anyMatch(band -> !isReadyForPromotion(next, band, now))) {
            return false;
        }

        BillingPriceVersionEntity current = active.isEmpty() ? null : active.getFirst();
        List<BillingPriceBandVersionEntity> currentBands = current == null
                ? List.of()
                : priceBandVersionRepository.findAllByPriceVersionIdForUpdate(current.getId());
        if (current != null && (currentBands.isEmpty()
                || currentBands.stream().anyMatch(band -> band.getStatus() != BillingPriceVersionStatus.ACTIVE))) {
            return false;
        }

        if (current != null) {
            current.setStatus(BillingPriceVersionStatus.RETIRED);
            currentBands.forEach(band -> band.setStatus(BillingPriceVersionStatus.RETIRED));
        }
        next.setStatus(BillingPriceVersionStatus.ACTIVE);
        nextBands.forEach(band -> band.setStatus(BillingPriceVersionStatus.ACTIVE));
        return true;
    }

    private static boolean isEffectiveAt(BillingPriceVersionEntity revision, LocalDateTime at) {
        return !revision.getEffectiveFrom().isAfter(at)
                && (revision.getEffectiveUntil() == null || at.isBefore(revision.getEffectiveUntil()));
    }

    private static boolean isReadyForPromotion(
            BillingPriceVersionEntity revision,
            BillingPriceBandVersionEntity band,
            LocalDateTime at) {
        return band.getStatus() == BillingPriceVersionStatus.SCHEDULED
                && revision.getId().equals(band.getPriceVersionId())
                && revision.getProductKind() == band.getProductKind()
                && revision.getProductKey().equals(band.getProductKey())
                && revision.getScopeKind() == band.getScopeKind()
                && revision.getEffectiveFrom().equals(band.getEffectiveFrom())
                && java.util.Objects.equals(revision.getEffectiveUntil(), band.getEffectiveUntil())
                && !band.getEffectiveFrom().isAfter(at)
                && (band.getEffectiveUntil() == null || at.isBefore(band.getEffectiveUntil()));
    }
}
