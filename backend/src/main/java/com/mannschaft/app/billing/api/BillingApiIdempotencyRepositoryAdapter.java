package com.mannschaft.app.billing.api;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BillingApiIdempotencyRepository} の JPA 実装（BC-23）。
 *
 * <p>金型は {@link BillingQuoteRepositoryAdapter}。状態遷移は JPA の
 * 条件付き UPDATE（CAS）だけで行い、read-modify-write はしない
 * （lease 所有者を跨いだ上書きを構造的に起こさないため）。</p>
 */
@Component
@RequiredArgsConstructor
class BillingApiIdempotencyRepositoryAdapter implements BillingApiIdempotencyRepository {

    private final BillingApiIdempotencyJpaRepository idempotencyJpaRepository;

    @Override
    public Optional<BillingIdempotencyRecord> find(
            long actorId, String httpMethod, String requestPath, String idempotencyKey) {
        if (httpMethod == null || requestPath == null || idempotencyKey == null) {
            return Optional.empty();
        }
        return idempotencyJpaRepository
                .findByActorIdAndHttpMethodAndRequestPathAndIdempotencyKey(
                        actorId, httpMethod, requestPath, idempotencyKey)
                .map(this::toRecord);
    }

    @Override
    public BillingIdempotencyRecord reserve(BillingIdempotencyRecord record) {
        BillingApiIdempotencyEntity entity = BillingApiIdempotencyEntity.builder()
                .actorId(record.actorId())
                .httpMethod(record.httpMethod())
                .requestPath(record.requestPath())
                .idempotencyKey(record.idempotencyKey())
                .requestHash(record.requestHash())
                .status(record.status())
                .responseStatus(record.responseStatus())
                .responseJson(record.responseJson())
                .leaseOwner(record.leaseOwner())
                .leaseExpiresAt(record.leaseExpiresAt())
                .startedAt(record.startedAt())
                .completedAt(record.completedAt())
                .expiresAt(record.expiresAt())
                .createdAt(record.startedAt())
                .build();
        return toRecord(idempotencyJpaRepository.saveAndFlush(entity));
    }

    @Override
    public int completeIfLeaseOwner(UUID id, String leaseOwner, int responseStatus,
                                    String responseJson, Instant completedAt) {
        if (id == null || leaseOwner == null) {
            return 0;
        }
        return idempotencyJpaRepository.completeIfLeaseOwner(
                id, leaseOwner, responseStatus, responseJson, completedAt);
    }

    @Override
    public int failIfLeaseOwner(UUID id, String leaseOwner, int responseStatus,
                                String responseJson, Instant completedAt) {
        if (id == null || leaseOwner == null) {
            return 0;
        }
        return idempotencyJpaRepository.failIfLeaseOwner(
                id, leaseOwner, responseStatus, responseJson, completedAt);
    }

    @Override
    public int recoverStaleLease(UUID id, String previousLeaseOwner, Instant observedExpiry,
                                 String newLeaseOwner, Instant newLeaseExpiry, Instant now) {
        if (id == null || previousLeaseOwner == null || observedExpiry == null) {
            return 0;
        }
        return idempotencyJpaRepository.recoverStaleLease(
                id, previousLeaseOwner, observedExpiry, newLeaseOwner, newLeaseExpiry, now);
    }

    private BillingIdempotencyRecord toRecord(BillingApiIdempotencyEntity entity) {
        return new BillingIdempotencyRecord(
                entity.getId(), entity.getActorId(), entity.getHttpMethod(), entity.getRequestPath(),
                entity.getIdempotencyKey(), entity.getRequestHash(), entity.getStatus(),
                entity.getResponseStatus(), entity.getResponseJson(), entity.getLeaseOwner(),
                entity.getLeaseExpiresAt(), entity.getStartedAt(), entity.getCompletedAt(),
                entity.getExpiresAt());
    }
}
