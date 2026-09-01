package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.api.dto.BillingQuoteResponse;
import com.mannschaft.app.billing.api.dto.CreateBillingQuoteRequest;
import lombok.RequiredArgsConstructor;

import java.time.Clock;

/** PR4 quote の公開契約をコンパイル可能にする未実装骨格。 */
@RequiredArgsConstructor
public class BillingQuoteService {
    private final Clock clock;
    private final BillingCheckoutScopeGuard scopeGuard;
    private final BillingQuoteRepository quoteRepository;
    private final BillingQuoteCalculator quoteCalculator;

    public BillingQuoteResponse create(long actorId, CreateBillingQuoteRequest request, String idempotencyKey) {
        throw new UnsupportedOperationException("PR4 quote service is not implemented");
    }
}
