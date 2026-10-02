package com.mannschaft.app.billing.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** V196 {@code billing_quotes} の一回消費 CAS query。 */
public interface BillingQuoteJpaRepository extends JpaRepository<BillingQuoteEntity, UUID> {

    Optional<BillingQuoteEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * quote を「所有 actor・観測 version・未消費・未失効」すべて一致のときだけ一度消費する。
     *
     * @return 更新行数（1 のときだけ消費成功）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BillingQuoteEntity entity
               set entity.consumedAt = :now,
                   entity.version = entity.version + 1
             where entity.id = :id
               and entity.actorId = :actorId
               and entity.version = :version
               and entity.consumedAt is null
               and entity.expiresAt > :now
               and entity.deletedAt is null
            """)
    int consumeIfUnchanged(@Param("id") UUID id,
                           @Param("actorId") long actorId,
                           @Param("version") long version,
                           @Param("now") Instant now);
}
