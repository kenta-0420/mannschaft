package com.mannschaft.app.billing.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** V196 billing_api_idempotencies の reservation/CAS 永続境界。 */
interface BillingApiIdempotencyRepository {
    Optional<BillingIdempotencyRecord> find(
            long actorId, String httpMethod, String requestPath, String idempotencyKey);

    BillingIdempotencyRecord reserve(BillingIdempotencyRecord record);

    int completeIfLeaseOwner(UUID id, String leaseOwner, int responseStatus,
                             String responseJson, Instant completedAt);

    int failIfLeaseOwner(UUID id, String leaseOwner, int responseStatus,
                         String responseJson, Instant completedAt);

    int recoverStaleLease(UUID id, String previousLeaseOwner, Instant observedExpiry,
                          String newLeaseOwner, Instant newLeaseExpiry, Instant now);
}
