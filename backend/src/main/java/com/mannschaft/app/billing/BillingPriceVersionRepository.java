package com.mannschaft.app.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link BillingPriceVersionEntity} の永続化・catalog revision 検索。 */
public interface BillingPriceVersionRepository extends JpaRepository<BillingPriceVersionEntity, UUID> {

    Optional<BillingPriceVersionEntity> findByIdAndDeletedAtIsNull(UUID id);

    List<BillingPriceVersionEntity> findByProductKindAndProductKeyAndScopeKindAndDeletedAtIsNullOrderByRevisionNoDesc(
            BillingProductKind productKind, String productKey, EntitlementScopeKind scopeKind);

    /** 指定時点に有効な候補を取得する。期間は半開区間 {@code [effectiveFrom, effectiveUntil)}。 */
    @Query("SELECT p FROM BillingPriceVersionEntity p "
            + "WHERE p.productKind = :productKind AND p.productKey = :productKey "
            + "AND p.scopeKind = :scopeKind AND p.status IN :statuses AND p.deletedAt IS NULL "
            + "AND p.effectiveFrom <= :at "
            + "AND (p.effectiveUntil IS NULL OR :at < p.effectiveUntil) "
            + "ORDER BY p.effectiveFrom DESC, p.revisionNo DESC")
    List<BillingPriceVersionEntity> findEffectiveCandidates(
            @Param("productKind") BillingProductKind productKind,
            @Param("productKey") String productKey,
            @Param("scopeKind") EntitlementScopeKind scopeKind,
            @Param("statuses") Collection<BillingPriceVersionStatus> statuses,
            @Param("at") Instant at);

    /** 同じ商品・スコープの revision を直列化するために悲観ロックして取得する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM BillingPriceVersionEntity p "
            + "WHERE p.productKind = :productKind AND p.productKey = :productKey "
            + "AND p.scopeKind = :scopeKind AND p.deletedAt IS NULL "
            + "ORDER BY p.effectiveFrom ASC, p.revisionNo ASC")
    List<BillingPriceVersionEntity> findAllForUpdate(
            @Param("productKind") BillingProductKind productKind,
            @Param("productKey") String productKey,
            @Param("scopeKind") EntitlementScopeKind scopeKind);
}
