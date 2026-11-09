package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /**
     * legacy PLAN専用の先行試練骨格。全requested keyへSelectionを返す契約を持つ。
     * 空Mapやkey不在を無料と同一視しない。既存selectNowには接続しない。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<PlanScopeKey, LegacyPlanSelection> selectLegacyPlans(Set<PlanScopeKey> keys) {
        throw new UnsupportedOperationException("CMP-260930-1931 試練骨格: 未実装");
    }

    /** PLANだけを対象とする要求identity。ADDONをこの入口へ含めない。 */
    public record PlanScopeKey(String planKey, EntitlementScopeKind scopeKind) { }

    /** 適格選択・新公開設定なし・公開済み販売不能を混同しない。 */
    public enum LegacyPlanSelectionState {
        SELECTED,
        NO_PUBLISHED_PRICE,
        NOT_SELLABLE
    }

    /**
     * SELECTEDのsnapshotは必須、それ以外はnullという返却不変条件。
     * NO_PUBLISHED_PRICEでも既存の明示0以外を無料化しない。
     */
    public record LegacyPlanSelection(LegacyPlanSelectionState state, LegacyPlanSnapshot snapshot) { }

    /** 検証を終えたPRIMARY TX内でcopyする不変出力。Entityやlazy参照を含めない。 */
    public record LegacyPlanSnapshot(UUID priceVersionId, List<LegacyPlanBandSnapshot> bands) { }

    /** catalog/人数帯選択/legacy保存に必要な検証済scalarだけを持つ。 */
    public record LegacyPlanBandSnapshot(
            UUID id, Integer bandNo, Integer minMembers, Integer maxMembers, Long amountIncludingTax) { }

    public record SelectedPrice(
            BillingPriceVersionEntity revision,
            List<BillingPriceBandVersionEntity> bands) {
    }
}
