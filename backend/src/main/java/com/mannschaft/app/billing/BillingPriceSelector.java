package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 公開pricingと将来のquoteが共用する、時点有効な販売価格の選択器。 */
@Service
@RequiredArgsConstructor
public class BillingPriceSelector {

    private final BillingPricePromotionService promotionService;
    private final BillingPriceSnapshotReader snapshotReader;
    private final Clock clock;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<SelectedPrice> selectNow(
            BillingProductKind productKind, String productKey, EntitlementScopeKind scopeKind) {
        Instant now = clock.instant();
        promotionService.promoteDue(productKind, productKey, scopeKind, now);

        Optional<BillingPriceSnapshotReader.Candidate> candidate =
                snapshotReader.readActive(productKind, productKey, scopeKind, now);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        BillingPriceVersionEntity revision = candidate.get().revision();
        List<BillingPriceBandVersionEntity> bands = candidate.get().bands();
        if (bands.stream().anyMatch(band -> !isSellable(revision, band))
                || !hasNonOverlappingRanges(bands)) {
            return Optional.empty();
        }
        return Optional.of(new SelectedPrice(revision, bands));
    }

    private static boolean isSellable(
            BillingPriceVersionEntity revision, BillingPriceBandVersionEntity band) {
        return revision.getId().equals(band.getPriceVersionId())
                && revision.getProductKind() == band.getProductKind()
                && revision.getProductKey().equals(band.getProductKey())
                && revision.getScopeKind() == band.getScopeKind()
                && Objects.equals(revision.getEffectiveFrom(), band.getEffectiveFrom())
                && Objects.equals(revision.getEffectiveUntil(), band.getEffectiveUntil())
                && "JPY".equals(band.getCurrency())
                && band.getInputAmount() != null && band.getInputAmount() > 0
                && band.getAmountIncludingTax() != null && band.getAmountIncludingTax() > 0
                && band.getAmountExcludingTax() != null && band.getAmountExcludingTax() >= 0
                && band.getTaxAmount() != null && band.getTaxAmount() >= 0
                && hasConsistentTotal(band)
                && band.getTaxRateBasisPoints() != null
                && band.getTaxRateBasisPoints() >= 0 && band.getTaxRateBasisPoints() <= 10_000
                && hasText(band.getTaxCodeSnapshot())
                && hasText(band.getTaxMasterSnapshot())
                && hasText(band.getTaxNameSnapshot())
                && band.getStripePriceRef() != null && !band.getStripePriceRef().isBlank()
                && band.getTaxBehavior() != null
                && band.isIncludedInPrice() == (band.getTaxBehavior() == BillingTaxBehavior.INCLUSIVE)
                && (band.getTaxBehavior() == BillingTaxBehavior.INCLUSIVE
                        ? band.getInputAmount().equals(band.getAmountIncludingTax())
                        : band.getInputAmount().equals(band.getAmountExcludingTax()));
    }

    private static boolean hasConsistentTotal(BillingPriceBandVersionEntity band) {
        try {
            return band.getAmountIncludingTax().equals(
                    Math.addExact(band.getAmountExcludingTax(), band.getTaxAmount()));
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasNonOverlappingRanges(List<BillingPriceBandVersionEntity> bands) {
        if (bands.stream().anyMatch(band -> band.getMinMembers() == null)) {
            return false;
        }
        int previousMax = 0;
        for (BillingPriceBandVersionEntity band : bands.stream()
                .sorted(java.util.Comparator.comparingInt(BillingPriceBandVersionEntity::getMinMembers))
                .toList()) {
            if (band.getMinMembers() == null || band.getMinMembers() < 1
                    || band.getMinMembers() <= previousMax
                    || (band.getMaxMembers() != null && band.getMaxMembers() < band.getMinMembers())) {
                return false;
            }
            if (band.getMaxMembers() == null) {
                previousMax = Integer.MAX_VALUE;
            } else {
                previousMax = band.getMaxMembers();
            }
        }
        return true;
    }

    public record SelectedPrice(
            BillingPriceVersionEntity revision,
            List<BillingPriceBandVersionEntity> bands) {
    }
}
