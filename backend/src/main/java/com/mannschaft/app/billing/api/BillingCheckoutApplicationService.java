package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.api.dto.CreateBillingCheckoutSessionRequest;

import java.time.Instant;

/** PR4 durable checkout の未実装骨格。 */
public class BillingCheckoutApplicationService {
    public record CheckoutSessionResponse(String checkoutUrl, Instant expiresAt) { }

    public CheckoutSessionResponse create(long actorId, CreateBillingCheckoutSessionRequest request,
                                          String idempotencyKey) {
        throw new UnsupportedOperationException("PR4 checkout service is not implemented");
    }
}
