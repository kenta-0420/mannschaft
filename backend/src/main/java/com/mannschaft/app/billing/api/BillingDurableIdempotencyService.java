package com.mannschaft.app.billing.api;

import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** PR4 durable idempotency の未実装骨格。 */
@RequiredArgsConstructor
class BillingDurableIdempotencyService {
    private final Clock clock;
    private final BillingApiIdempotencyRepository repository;

    BillingIdempotencyDecision begin(long actorId, String httpMethod, String requestPath,
                                     String idempotencyKey, String requestHash, String leaseOwner) {
        throw new UnsupportedOperationException("PR4 idempotency reservation is not implemented");
    }

    void complete(UUID id, String leaseOwner, int responseStatus, String responseJson) {
        throw new UnsupportedOperationException("PR4 idempotency completion is not implemented");
    }

    void recoverStale(UUID id, String previousLeaseOwner, Instant observedExpiry,
                      String newLeaseOwner) {
        throw new UnsupportedOperationException("PR4 stale lease recovery is not implemented");
    }
}

enum BillingIdempotencyStatus { PROCESSING, SUCCEEDED, FAILED }

enum BillingIdempotencyDecisionKind { ACQUIRED, REPLAY, PROCESSING }

record BillingIdempotencyRecord(
        UUID id, long actorId, String httpMethod, String requestPath,
        String idempotencyKey, String requestHash, BillingIdempotencyStatus status,
        Integer responseStatus, String responseJson, String leaseOwner,
        Instant leaseExpiresAt, Instant startedAt, Instant completedAt, Instant expiresAt) { }

record BillingIdempotencyDecision(
        BillingIdempotencyDecisionKind kind, UUID id, Integer responseStatus,
        String responseJson, long retryAfterSeconds) { }
