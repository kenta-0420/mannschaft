package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.dto.BillingQuoteResponse;
import com.mannschaft.app.billing.api.dto.CreateBillingQuoteRequest;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * PR4 quote（BC-03/BC-13）。
 *
 * <p>入力検証 → JST 月境界判定 → scope 認可 → 見積り計算 → snapshot 保存の順に進む。
 * 検証・月境界で弾く場合は calculator/repository へ一切触れない fail-closed とする。</p>
 */
@RequiredArgsConstructor
public class BillingQuoteService {

    /** quote の有効期間（BC-13: 正確に10分）。 */
    private static final Duration QUOTE_TTL = Duration.ofMinutes(10);

    /** Stripe server/API 遅延を吸収する Session 期限の安全margin（BC-13）。 */
    private static final Duration SESSION_SAFETY_MARGIN = Duration.ofSeconds(60);

    /** Session 期限の上限（BC-13: now+23h59m）。 */
    private static final Duration SESSION_MAX_WINDOW = Duration.ofHours(23).plusMinutes(59);

    /** Session 作成に必要な最小残り期間（BC-13: 30分+60秒）。 */
    private static final Duration MINIMUM_SESSION_WINDOW = Duration.ofMinutes(30).plusSeconds(60);

    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Tokyo");

    private final Clock clock;
    private final BillingCheckoutScopeGuard scopeGuard;
    private final BillingQuoteRepository quoteRepository;
    private final BillingQuoteCalculator quoteCalculator;

    public BillingQuoteResponse create(long actorId, CreateBillingQuoteRequest request, String idempotencyKey) {
        validate(request);

        Instant now = clock.instant();
        rejectWhenMonthBoundary(now);

        scopeGuard.check(actorId, request.scopeKind(), request.scopeId());
        BillingQuoteSnapshot calculated = quoteCalculator.calculate(actorId, request, now);
        BillingQuoteSnapshot saved = quoteRepository.save(withExpiry(calculated, now.plus(QUOTE_TTL)));
        return toResponse(saved);
    }

    /** scope/productKey の入力不備は 009（400）で fail-closed に拒否する。 */
    private void validate(CreateBillingQuoteRequest request) {
        if (request == null
                || request.scopeKind() == null
                || request.scopeId() == null
                || request.scopeId() <= 0L
                || request.productKey() == null
                || request.productKey().isBlank()) {
            throw new BusinessException(EntitlementErrorCode.INVALID_SCOPE_KIND);
        }
    }

    /**
     * BC-13: Session 期限 {@code min(now+23h59m, 翌月1日00:00 JST - 60秒)} までの残りが
     * 30分+60秒 未満なら 022（409）と翌月1日 00:00 JST を返す。
     */
    private void rejectWhenMonthBoundary(Instant now) {
        Instant nextMonthStart = nextMonthStart(now);
        Instant sessionExpiry = min(now.plus(SESSION_MAX_WINDOW), nextMonthStart.minus(SESSION_SAFETY_MARGIN));
        if (Duration.between(now, sessionExpiry).compareTo(MINIMUM_SESSION_WINDOW) < 0) {
            throw new BillingConflictException(EntitlementErrorCode.MONTH_BOUNDARY,
                    new BillingConflictException.BillingConflictDetails(
                            BillingConflictException.Reason.MONTH_BOUNDARY, nextMonthStart, null));
        }
    }

    /** JST の翌月1日 00:00（閏年・年跨ぎは {@link LocalDate} の暦計算に委ねる）。 */
    private Instant nextMonthStart(Instant now) {
        return LocalDate.ofInstant(now, BILLING_ZONE)
                .withDayOfMonth(1)
                .plusMonths(1)
                .atStartOfDay(BILLING_ZONE)
                .toInstant();
    }

    private Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private BillingQuoteSnapshot withExpiry(BillingQuoteSnapshot snapshot, Instant expiresAt) {
        return new BillingQuoteSnapshot(
                snapshot.quoteId(), snapshot.actorId(), snapshot.scopeKind(), snapshot.scopeId(),
                snapshot.billingCustomerId(), snapshot.productKind(), snapshot.productKey(),
                snapshot.priceBandVersionId(), snapshot.stripePriceRef(), snapshot.memberCount(),
                snapshot.initialTotal(), snapshot.nextMonthlyTotal(), snapshot.taxSnapshot(),
                snapshot.periodStart(), snapshot.periodEnd(), snapshot.prorationAt(),
                snapshot.contractVersion(), snapshot.requestHash(), expiresAt,
                snapshot.consumedAt(), snapshot.version());
    }

    private BillingQuoteResponse toResponse(BillingQuoteSnapshot snapshot) {
        return new BillingQuoteResponse(
                snapshot.quoteId(), snapshot.productKind(), snapshot.productKey(),
                toMoney(snapshot.initialTotal()), toMoney(snapshot.nextMonthlyTotal()),
                snapshot.expiresAt(), snapshot.periodStart(), snapshot.periodEnd());
    }

    private BillingQuoteResponse.Money toMoney(BillingMoney money) {
        if (money == null) {
            return null;
        }
        return new BillingQuoteResponse.Money(money.currency(), money.amountIncludingTax(),
                money.amountExcludingTax(), money.taxAmount(), money.taxName(), money.taxRateBasisPoints());
    }
}
