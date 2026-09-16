package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.api.dto.CreateBillingCheckoutSessionRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PR4 durable checkout（BC-13 / BC-23）。
 *
 * <p>quote 取得 → scope 認可 → snapshot 再検証 → Customer/価格の再検証 → 契約枠の予約 →
 * Stripe Checkout Session 作成 → quote の CAS 消費 → session 紐付け、の順に進む。
 * 再検証で弾く場合は Stripe を一切呼ばない fail-closed とし、Stripe 成功後に DB 側が
 * 倒れた場合は照合キューへ退避してから 502 を返す（黙って握りつぶさない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingCheckoutApplicationService {
    public record CheckoutSessionResponse(String checkoutUrl, Instant expiresAt) { }

    /** 耐久冪等性（BC-23）が本 API を識別するための HTTP method / path の正本。 */
    static final String IDEMPOTENCY_METHOD = "POST";
    static final String IDEMPOTENCY_PATH = "/api/v1/me/billing/checkout-sessions";

    /** PENDING 契約の補償にも失敗した場合の運用アラート marker。 */
    static final String ORPHAN_PENDING_MARKER = "BILLING_CHECKOUT_ORPHAN_PENDING_CONTRACT";

    /** Session 期限の上限（BC-13: now+23h59m）。 */
    private static final Duration SESSION_MAX_WINDOW = Duration.ofHours(23).plusMinutes(59);

    /** 月境界手前で Session を締める安全margin（BC-13: 翌月初 60 秒前）。 */
    private static final Duration SESSION_SAFETY_MARGIN = Duration.ofSeconds(60);

    /** Customer が Checkout に使える唯一の状態。 */
    private static final String CUSTOMER_ACTIVE = "ACTIVE";

    /** 課金の月境界判定に用いるサーバー基準ゾーン（正本: {@link UserZoneLocalDateTimeParser#SERVER_ZONE}）。 */
    private static final ZoneId BILLING_ZONE = UserZoneLocalDateTimeParser.SERVER_ZONE;

    /** 月境界（JST）判定を行うため壁時計を用いる。ゾーンは {@link #BILLING_ZONE} で明示する。 */
    @Qualifier("wallClock")
    private final Clock clock;
    private final BillingCheckoutAccessGuard scopeGuard;
    private final BillingQuoteRepository quoteRepository;
    private final BillingCheckoutCustomerRepository customerRepository;
    private final BillingCheckoutPriceRepository priceRepository;
    private final BillingCheckoutContractRepository contractRepository;
    /**
     * BC-23 の耐久冪等性。begin/complete による API 応答の replay は HTTP 入口
     * （{@link BillingCheckoutController}）が担当する（begin の 3 分岐は HTTP 応答へ写す必要があり、
     * かつ quote 側の入口と同一の作法で包む必要があるため）。本サービスは、照合キューへ退避する行に
     * <b>当該 API 呼び出しの冪等レコード id</b> を刻むために参照する（退避行から「どの再送で
     * 孤児 Session が生まれたか」を辿れるようにするため。乱数を刻むと追跡不能になる）。
     * Stripe 側の二重実行は本サービスが渡す stripeIdempotencyKey で塞ぐ。
     */
    private final BillingDurableIdempotencyService idempotencyService;
    private final BillingStripeCheckoutGateway stripeCheckoutGateway;
    private final BillingCheckoutReconciliationQueue reconciliationQueue;

    public CheckoutSessionResponse create(long actorId, CreateBillingCheckoutSessionRequest request,
                                          String idempotencyKey) {
        UUID quoteId = request == null ? null : request.quoteId();
        if (quoteId == null) {
            throw new BusinessException(EntitlementErrorCode.INVALID_SCOPE_KIND);
        }
        Instant now = clock.instant();

        // 存在オラクルを作らないため、未存在・他 actor の quote は失効と同じ 409 に畳む。
        BillingQuoteSnapshot quote = quoteRepository.findById(quoteId)
                .filter(found -> found.actorId() == actorId)
                .orElseThrow(() -> conflict(BillingConflictException.Reason.QUOTE_EXPIRED, quoteId));

        scopeGuard.check(actorId, quote.scopeKind(), quote.scopeId());

        // BC-13: Stripe へ出る前に quote snapshot を現在時刻から導いた値と突き合わせる。
        verifySnapshotFresh(quote, now);

        BillingCheckoutCustomer customer =
                customerRepository.findScopeOwnedActive(quote.scopeKind(), quote.scopeId(),
                                quote.billingCustomerId())
                        .filter(found -> CUSTOMER_ACTIVE.equals(found.status()))
                        .orElseThrow(() -> new BusinessException(EntitlementErrorCode.MIGRATION_REQUIRED));

        // inline price_data は使わず、保存済み Stripe Price が販売可能なことを確かめる。
        if (!priceRepository.isExistingSellablePrice(quote.priceBandVersionId(), quote.stripePriceRef())) {
            throw new BusinessException(EntitlementErrorCode.PRICE_NOT_SELLABLE);
        }

        UUID contractId = contractRepository.reservePendingContract(quote, actorId);

        // Stripe 呼び出しは外部 I/O。ここが倒れると PENDING 契約が孤児として残り uk_acp_slot を占有し、
        // 当該 scope の以後の購入が永久に 016 で詰む（Session が無いので expired webhook でも解放されない）。
        // 既存決済フロー（BillingCheckoutService#startPaidContract）と同じ流儀で必ず補償する。
        BillingStripeCheckoutResult result;
        try {
            result = stripeCheckoutGateway.createSubscription(
                    stripeRequest(quote, customer, contractId, now, idempotencyKey));
        } catch (RuntimeException e) {
            releasePendingContract(contractId, e);
            throw new BusinessException(EntitlementErrorCode.CHECKOUT_SESSION_FAILED, e);
        }

        // ここから先の失敗は「Stripe 上に Session が実在する」状態での失敗。必ず照合キューへ残す。
        try {
            if (quoteRepository.consumeIfUnchanged(quote.quoteId(), actorId, quote.version(), now) != 1) {
                throw new IllegalStateException("quote CAS consume did not affect exactly one row");
            }
            contractRepository.attachStripeSession(contractId, result.sessionId());
        } catch (RuntimeException e) {
            reconciliationQueue.enqueue(result.sessionId(), customer.stripeCustomerRef(),
                    idempotencyRecordId(actorId, idempotencyKey));
            throw new BusinessException(EntitlementErrorCode.STRIPE_UNAVAILABLE, e);
        }

        return new CheckoutSessionResponse(result.checkoutUrl(), result.expiresAt());
    }

    /**
     * Stripe 作成失敗の補償。PENDING 契約を解放してスロットを空ける（冪等）。
     *
     * <p>補償自体が落ちた場合も事実を失わない。ここで再送出すると呼び出し元の
     * 「015 で上申する」経路そのものを失い、しかも孤児が残った事実まで消えるため、
     * marker 付き ERROR ログに残して記録に徹する（握りつぶしではない）。
     * Checkout URL・token・PII は出さず、契約 id だけを出す。</p>
     */
    private void releasePendingContract(UUID contractId, RuntimeException cause) {
        log.error("Stripe Checkout Session の作成に失敗。PENDING 契約を補償します contractId={}", contractId, cause);
        try {
            contractRepository.abandonPendingContract(contractId);
        } catch (RuntimeException compensationFailure) {
            log.error("{} PENDING 契約の補償に失敗（スロットが占有されたまま・要手動解放）contractId={}",
                    ORPHAN_PENDING_MARKER, contractId, compensationFailure);
        }
    }

    /**
     * 照合キューへ刻む冪等レコード id。取得できない場合だけ乱数へ退避する
     * （退避行そのものを失わないため。id が無いことを理由に事実を捨てない）。
     */
    private UUID idempotencyRecordId(long actorId, String idempotencyKey) {
        try {
            Optional<UUID> found = idempotencyService.findRecordId(
                    actorId, IDEMPOTENCY_METHOD, IDEMPOTENCY_PATH, idempotencyKey);
            if (found != null && found.isPresent()) {
                return found.get();
            }
        } catch (RuntimeException e) {
            log.warn("冪等レコード id の解決に失敗したため照合キューには代替 id を刻みます", e);
        }
        return UUID.randomUUID();
    }

    /**
     * BC-13: 期限切れ、および period/proration が現在の JST 月から導かれる値と食い違う quote は
     * 消費せず 023（QUOTE_STALE）で拒否する。
     */
    private void verifySnapshotFresh(BillingQuoteSnapshot quote, Instant now) {
        if (quote.consumedAt() != null || quote.expiresAt() == null || !quote.expiresAt().isAfter(now)) {
            throw conflict(BillingConflictException.Reason.QUOTE_STALE, quote.quoteId());
        }
        Instant periodStart = monthStart(now);
        Instant periodEnd = nextMonthStart(now);
        if (!periodStart.equals(quote.periodStart()) || !periodEnd.equals(quote.periodEnd())) {
            throw conflict(BillingConflictException.Reason.QUOTE_STALE, quote.quoteId());
        }
        Instant prorationAt = quote.prorationAt();
        if (prorationAt == null || prorationAt.isBefore(periodStart) || prorationAt.isAfter(now)) {
            throw conflict(BillingConflictException.Reason.QUOTE_STALE, quote.quoteId());
        }
    }

    /** Session 期限は min(now+23h59m, JST 翌月1日00:00 - 60秒)。 */
    private Instant sessionExpiry(Instant now) {
        Instant capped = nextMonthStart(now).minus(SESSION_SAFETY_MARGIN);
        Instant maxWindow = now.plus(SESSION_MAX_WINDOW);
        return maxWindow.isBefore(capped) ? maxWindow : capped;
    }

    private BillingStripeCheckoutRequest stripeRequest(BillingQuoteSnapshot quote,
                                                       BillingCheckoutCustomer customer,
                                                       UUID contractId, Instant now,
                                                       String idempotencyKey) {
        // PII は載せず、突合に要る4点ちょうどを session/subscription 双方へ付ける。
        Map<String, String> metadata = Map.of(
                "billingContractId", contractId.toString(),
                "scopeKind", quote.scopeKind().name(),
                "scopeId", Long.toString(quote.scopeId()),
                "billingCustomerId", customer.id().toString());
        return new BillingStripeCheckoutRequest(
                customer.stripeCustomerRef(),
                quote.stripePriceRef(),
                sessionExpiry(now),
                metadata,
                metadata,
                // 復帰 URL は return state（署名付き）を握る gateway 実装側で組み立てる。
                null,
                null,
                // 再送時に Stripe 側で同一 Session が返るよう、呼び出し元の冪等キーへ束縛する。
                stripeIdempotencyKey(contractId, idempotencyKey));
    }

    private String stripeIdempotencyKey(UUID contractId, String idempotencyKey) {
        return Optional.ofNullable(idempotencyKey)
                .filter(key -> !key.isBlank())
                .map(key -> "billing-checkout:" + key)
                .orElse("billing-checkout:" + contractId);
    }

    private Instant monthStart(Instant now) {
        return LocalDate.ofInstant(now, BILLING_ZONE)
                .withDayOfMonth(1).atStartOfDay(BILLING_ZONE).toInstant();
    }

    private Instant nextMonthStart(Instant now) {
        return LocalDate.ofInstant(now, BILLING_ZONE)
                .withDayOfMonth(1).plusMonths(1).atStartOfDay(BILLING_ZONE).toInstant();
    }

    private BillingConflictException conflict(BillingConflictException.Reason reason, UUID quoteId) {
        return new BillingConflictException(EntitlementErrorCode.QUOTE_EXPIRED,
                new BillingConflictException.BillingConflictDetails(reason, null, quoteId));
    }
}
