package com.mannschaft.app.billing.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** V196 idempotency lease の所有者付き CAS query。 */
public interface BillingApiIdempotencyJpaRepository
        extends JpaRepository<BillingApiIdempotencyEntity, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BillingApiIdempotencyEntity entity
               set entity.status = com.mannschaft.app.billing.api.BillingIdempotencyStatus.SUCCEEDED,
                   entity.responseStatus = :responseStatus,
                   entity.responseJson = :responseJson,
                   entity.completedAt = :completedAt
             where entity.id = :id
               and entity.status = com.mannschaft.app.billing.api.BillingIdempotencyStatus.PROCESSING
               and entity.leaseOwner = :leaseOwner
            """)
    int completeIfLeaseOwner(@Param("id") UUID id,
                             @Param("leaseOwner") String leaseOwner,
                             @Param("responseStatus") int responseStatus,
                             @Param("responseJson") String responseJson,
                             @Param("completedAt") Instant completedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BillingApiIdempotencyEntity entity
               set entity.leaseOwner = :newLeaseOwner,
                   entity.leaseExpiresAt = :newLeaseExpiry
             where entity.id = :id
               and entity.status = com.mannschaft.app.billing.api.BillingIdempotencyStatus.PROCESSING
               and entity.leaseOwner = :previousLeaseOwner
               and entity.leaseExpiresAt = :observedExpiry
               and entity.leaseExpiresAt <= :now
            """)
    int recoverStaleLease(@Param("id") UUID id,
                          @Param("previousLeaseOwner") String previousLeaseOwner,
                          @Param("observedExpiry") Instant observedExpiry,
                          @Param("newLeaseOwner") String newLeaseOwner,
                          @Param("newLeaseExpiry") Instant newLeaseExpiry,
                          @Param("now") Instant now);
}
