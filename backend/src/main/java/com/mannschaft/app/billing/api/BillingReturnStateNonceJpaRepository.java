package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** V196 {@code billing_return_state_nonces} の一回消費 CAS query。 */
public interface BillingReturnStateNonceJpaRepository
        extends JpaRepository<BillingReturnStateNonceEntity, UUID> {

    /**
     * hash / purpose / actor / scope の束縛がすべて一致し、未消費・未失効のときだけ一度消費する。
     *
     * @return 更新行数（1 のときだけ消費成功）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BillingReturnStateNonceEntity entity
               set entity.consumedAt = :now
             where entity.nonceHash = :nonceHash
               and entity.purpose = :purpose
               and entity.actorId = :actorId
               and entity.scopeKind = :scopeKind
               and entity.scopeId = :scopeId
               and entity.consumedAt is null
               and entity.expiresAt > :now
            """)
    int consumeIfValid(@Param("nonceHash") String nonceHash,
                       @Param("purpose") BillingReturnStateService.Purpose purpose,
                       @Param("actorId") long actorId,
                       @Param("scopeKind") EntitlementScopeKind scopeKind,
                       @Param("scopeId") long scopeId,
                       @Param("now") Instant now);
}
