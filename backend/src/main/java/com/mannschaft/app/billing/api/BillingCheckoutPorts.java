package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.CreateBillingQuoteRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** PR4 試練が固定する application service の境界。実装は出陣で行う。 */
interface BillingCheckoutAccessGuard {
    void check(long actorId, EntitlementScopeKind scopeKind, long scopeId);
}

interface BillingQuoteCalculator {
    BillingQuoteSnapshot calculate(long actorId, CreateBillingQuoteRequest request, Instant now);
}

interface BillingQuoteRepository {
    BillingQuoteSnapshot save(BillingQuoteSnapshot quote);

    Optional<BillingQuoteSnapshot> findById(UUID quoteId);

    int consumeIfUnchanged(UUID quoteId, long actorId, long version, Instant now);
}

interface BillingCheckoutCustomerRepository {
    Optional<BillingCheckoutCustomer> findScopeOwnedActive(
            EntitlementScopeKind scopeKind, long scopeId, UUID billingCustomerId);
}

interface BillingCheckoutPriceRepository {
    boolean isExistingSellablePrice(UUID priceBandVersionId, String stripePriceRef);
}

interface BillingCheckoutContractRepository {
    UUID reservePendingContract(BillingQuoteSnapshot quote, long actorId);

    void attachStripeSession(UUID contractId, String stripeSessionId);
}

interface BillingStripeCheckoutGateway {
    BillingStripeCheckoutResult createSubscription(BillingStripeCheckoutRequest request);
}

interface BillingCheckoutReconciliationQueue {
    void enqueue(String stripeSessionId, String stripeCustomerRef, UUID idempotencyId);
}

interface BillingReturnSigningKeyProvider {
    SigningKey activeKey();

    Optional<SigningKey> findByKid(String kid);

    record SigningKey(String kid, byte[] secret) { }
}

record BillingMoney(String currency, long amountIncludingTax, long amountExcludingTax,
                    long taxAmount, String taxName, Integer taxRateBasisPoints) { }

record BillingQuoteSnapshot(
        UUID quoteId,
        long actorId,
        EntitlementScopeKind scopeKind,
        long scopeId,
        UUID billingCustomerId,
        BillingProductKind productKind,
        String productKey,
        UUID priceBandVersionId,
        String stripePriceRef,
        Integer memberCount,
        BillingMoney initialTotal,
        BillingMoney nextMonthlyTotal,
        String taxSnapshot,
        Instant periodStart,
        Instant periodEnd,
        Instant prorationAt,
        Long contractVersion,
        String requestHash,
        Instant expiresAt,
        Instant consumedAt,
        long version) { }

record BillingCheckoutCustomer(UUID id, EntitlementScopeKind scopeKind, long scopeId,
                               String stripeCustomerRef, String status) { }

record BillingStripeCheckoutRequest(
        String stripeCustomerRef,
        String stripePriceRef,
        Instant expiresAt,
        Map<String, String> sessionMetadata,
        Map<String, String> subscriptionMetadata,
        String successUrl,
        String cancelUrl,
        String stripeIdempotencyKey) { }

record BillingStripeCheckoutResult(String sessionId, String checkoutUrl, Instant expiresAt) { }
