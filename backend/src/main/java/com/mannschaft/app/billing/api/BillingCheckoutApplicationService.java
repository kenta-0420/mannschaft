package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.api.dto.CreateBillingCheckoutSessionRequest;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.time.Instant;

/** PR4 durable checkout の公開契約をコンパイル可能にする未実装骨格。 */
@RequiredArgsConstructor
public class BillingCheckoutApplicationService {
    public record CheckoutSessionResponse(String checkoutUrl, Instant expiresAt) { }

    private final Clock clock;
    private final BillingCheckoutScopeGuard scopeGuard;
    private final BillingQuoteRepository quoteRepository;
    private final BillingCheckoutCustomerRepository customerRepository;
    private final BillingCheckoutPriceRepository priceRepository;
    private final BillingCheckoutContractRepository contractRepository;
    private final BillingDurableIdempotencyService idempotencyService;
    private final BillingStripeCheckoutGateway stripeCheckoutGateway;
    private final BillingCheckoutReconciliationQueue reconciliationQueue;

    public CheckoutSessionResponse create(long actorId, CreateBillingCheckoutSessionRequest request,
                                          String idempotencyKey) {
        throw new UnsupportedOperationException("PR4 checkout service is not implemented");
    }
}
