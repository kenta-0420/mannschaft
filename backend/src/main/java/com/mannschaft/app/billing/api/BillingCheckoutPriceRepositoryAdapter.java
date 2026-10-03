package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * {@link BillingCheckoutPriceRepository} の JPA 実装。
 *
 * <p>inline {@code price_data} を使わない方針のため、「保存済み price band が、Checkout 時点で
 * 販売可能（ACTIVE・有効期間内・quote が焼き付けた Stripe Price と一致）」であることだけを見る。
 * 既存 {@link BillingPriceBandVersionRepository#findByIdAndDeletedAtIsNull} を再利用する。</p>
 */
@Component
@RequiredArgsConstructor
class BillingCheckoutPriceRepositoryAdapter implements BillingCheckoutPriceRepository {

    private final BillingPriceBandVersionRepository priceBandVersionRepository;
    private final Clock clock;

    @Override
    public boolean isExistingSellablePrice(java.util.UUID priceBandVersionId, String stripePriceRef) {
        if (priceBandVersionId == null || stripePriceRef == null || stripePriceRef.isBlank()) {
            return false;
        }
        Instant now = clock.instant();
        return priceBandVersionRepository.findByIdAndDeletedAtIsNull(priceBandVersionId)
                .filter(band -> band.getStatus() == BillingPriceVersionStatus.ACTIVE)
                .filter(band -> stripePriceRef.equals(band.getStripePriceRef()))
                .filter(band -> isEffective(band, now))
                .isPresent();
    }

    private boolean isEffective(BillingPriceBandVersionEntity band, Instant now) {
        return band.getEffectiveFrom() != null
                && !band.getEffectiveFrom().isAfter(now)
                && (band.getEffectiveUntil() == null || band.getEffectiveUntil().isAfter(now));
    }
}
