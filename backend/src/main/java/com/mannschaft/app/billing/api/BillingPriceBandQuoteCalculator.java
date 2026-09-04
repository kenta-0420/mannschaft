package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.billing.api.dto.CreateBillingQuoteRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

/**
 * {@link BillingQuoteCalculator} の実装（BC-03/BC-13 の見積り計算）。
 *
 * <p>販売正本は {@code billing_price_band_versions}（inline {@code price_data} も
 * {@code plans.base_monthly_price_jpy} も使わない）。人数は
 * {@link ScopeMemberCountService}（USER は 1・TEAM/ORG は {@code memberships} の
 * アクティブ DISTINCT 人数）で解決し、product/scope/ACTIVE/有効期間/人数 band で価格版を引く。</p>
 *
 * <p>period は JST の当月（正本ゾーンは {@link UserZoneLocalDateTimeParser#SERVER_ZONE}）。
 * 新規契約は契約日から当月末までの日割りを {@code initialTotal}、翌月満額を
 * {@code nextMonthlyTotal} とする。税は band の税 snapshot をそのまま写し、Stripe Tax の
 * {@code automatic_tax} は用いない。</p>
 */
@Component
@RequiredArgsConstructor
class BillingPriceBandQuoteCalculator implements BillingQuoteCalculator {

    /** 課金の月境界・日割り基準ゾーン（正本）。 */
    private static final ZoneId BILLING_ZONE = UserZoneLocalDateTimeParser.SERVER_ZONE;

    /** Checkout に出せる価格版の状態（販売中のみ）。 */
    private static final List<BillingPriceVersionStatus> SELLABLE =
            List.of(BillingPriceVersionStatus.ACTIVE);

    /** Checkout に使える唯一の Customer 状態。 */
    private static final String CUSTOMER_ACTIVE = "ACTIVE";

    private final BillingPriceBandVersionRepository priceBandVersionRepository;
    private final BillingCustomerJpaRepository customerJpaRepository;
    private final ScopeMemberCountService scopeMemberCountService;

    @Override
    public BillingQuoteSnapshot calculate(long actorId, CreateBillingQuoteRequest request, Instant now) {
        BillingProductKind productKind = productKind(request.productKind());
        EntitlementScopeKind scopeKind = request.scopeKind();
        long scopeId = request.scopeId();

        int memberCount = scopeMemberCountService.countActiveMembers(scopeKind, scopeId);
        BillingPriceBandVersionEntity band = priceBandVersionRepository.findEffectiveCandidates(
                        productKind, request.productKey(), scopeKind, SELLABLE, now, memberCount)
                .stream()
                .filter(candidate -> candidate.getStripePriceRef() != null
                        && !candidate.getStripePriceRef().isBlank())
                .findFirst()
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.PRICE_NOT_SELLABLE));

        BillingCustomerEntity customer = customerJpaRepository
                .findByScopeKindAndScopeIdAndStatusAndDeletedAtIsNull(scopeKind, scopeId, CUSTOMER_ACTIVE)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.MIGRATION_REQUIRED));

        LocalDate today = LocalDate.ofInstant(now, BILLING_ZONE);
        Instant periodStart = today.withDayOfMonth(1).atStartOfDay(BILLING_ZONE).toInstant();
        Instant periodEnd = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(BILLING_ZONE).toInstant();

        BillingMoney monthly = money(band);
        // 新規契約は契約日から当月末まで日割り。翌月1日以降は満額（nextMonthlyTotal）。
        int daysInMonth = today.lengthOfMonth();
        int remainingDays = daysInMonth - today.getDayOfMonth() + 1;
        BillingMoney initial = prorate(monthly, remainingDays, daysInMonth);

        return new BillingQuoteSnapshot(
                null, actorId, scopeKind, scopeId, customer.getId(), productKind,
                request.productKey(), band.getId(), band.getStripePriceRef(),
                scopeKind == EntitlementScopeKind.USER ? null : memberCount,
                initial, monthly, band.getTaxMasterSnapshot(),
                periodStart, periodEnd, now, null,
                requestHash(actorId, scopeKind, scopeId, productKind, request.productKey(),
                        band, memberCount),
                // expiresAt は BillingQuoteService が now+10分で確定する。
                null, null, 0L);
    }

    private BillingProductKind productKind(String rawProductKind) {
        if (rawProductKind == null || rawProductKind.isBlank()) {
            return BillingProductKind.PLAN;
        }
        try {
            return BillingProductKind.valueOf(rawProductKind);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(EntitlementErrorCode.INVALID_CONTRACT_KIND, e);
        }
    }

    private BillingMoney money(BillingPriceBandVersionEntity band) {
        return new BillingMoney(band.getCurrency(), band.getAmountIncludingTax(),
                band.getAmountExcludingTax(), band.getTaxAmount(),
                band.getTaxNameSnapshot(), band.getTaxRateBasisPoints());
    }

    /**
     * 税込・税抜を同じ日割り比率で按分し、税額は差分で整合させる（税込 = 税抜 + 税額 を崩さない）。
     */
    private BillingMoney prorate(BillingMoney monthly, int remainingDays, int daysInMonth) {
        if (remainingDays >= daysInMonth) {
            return monthly;
        }
        long includingTax = share(monthly.amountIncludingTax(), remainingDays, daysInMonth);
        long excludingTax = share(monthly.amountExcludingTax(), remainingDays, daysInMonth);
        return new BillingMoney(monthly.currency(), includingTax, excludingTax,
                includingTax - excludingTax, monthly.taxName(), monthly.taxRateBasisPoints());
    }

    /** 円は最小単位が 1 のため四捨五入で整数化する。 */
    private long share(long amount, int remainingDays, int daysInMonth) {
        return (amount * remainingDays + daysInMonth / 2L) / daysInMonth;
    }

    /**
     * quote の同一性判定に使う要求ハッシュ（PII は含めない）。
     */
    private String requestHash(long actorId, EntitlementScopeKind scopeKind, long scopeId,
                               BillingProductKind productKind, String productKey,
                               BillingPriceBandVersionEntity band, int memberCount) {
        String raw = String.join("|", Long.toString(actorId), scopeKind.name(), Long.toString(scopeId),
                productKind.name(), productKey, band.getId().toString(), Integer.toString(memberCount));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
