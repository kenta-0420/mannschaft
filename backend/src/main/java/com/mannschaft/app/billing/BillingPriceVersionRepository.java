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
public interface BillingPriceVersionRepository
        extends JpaRepository<BillingPriceVersionEntity, UUID>, BillingPriceVersionRepositoryCustom {

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

    /** DB照合規則でPlanへ結び、保存canonical keyだけをdue候補として投影する。 */
    @Query("""
            SELECT DISTINCT plan.planKey AS planKey, price.scopeKind AS scopeKind
            FROM PlanEntity plan
            JOIN BillingPriceVersionEntity price ON price.productKey = plan.planKey
            WHERE plan.planKey IN :planKeys AND price.scopeKind IN :scopeKinds
              AND price.productKind = :productKind AND price.status = :scheduledStatus
              AND price.deletedAt IS NULL AND price.effectiveFrom <= :at
              AND (price.effectiveUntil IS NULL OR :at < price.effectiveUntil)
            """)
    List<LegacyPlanDueKey> findLegacyPlanDueKeys(
            @Param("planKeys") Collection<String> planKeys,
            @Param("scopeKinds") Collection<EntitlementScopeKind> scopeKinds,
            @Param("productKind") BillingProductKind productKind,
            @Param("scheduledStatus") BillingPriceVersionStatus scheduledStatus,
            @Param("at") Instant at);

    /**
     * Plan anchorを保ち、全ACTIVEと非ACTIVE公開履歴marker最大1件/keyを同SQLで読む。
     * 親が無いPlanも返すため、価格条件はWHEREではなくLEFT JOINのONへ置く。
     */
    @Query("""
            SELECT plan.planKey AS planKey, price AS revision
            FROM PlanEntity plan
            LEFT JOIN BillingPriceVersionEntity price ON price.productKey = plan.planKey
              AND price.productKind = :productKind AND price.scopeKind IN :scopeKinds
              AND price.deletedAt IS NULL
              AND (price.status = :activeStatus OR (
                (price.status = :retiredStatus OR
                  (price.status = :scheduledStatus AND price.effectiveFrom <= :at))
                AND price.revisionNo = (
                  SELECT MAX(marker.revisionNo) FROM BillingPriceVersionEntity marker
                  WHERE marker.productKind = price.productKind AND marker.productKey = price.productKey
                    AND marker.scopeKind = price.scopeKind AND marker.deletedAt IS NULL
                    AND (marker.status = :retiredStatus OR
                      (marker.status = :scheduledStatus AND marker.effectiveFrom <= :at))
                )
              ))
            WHERE plan.planKey IN :planKeys
            """)
    List<LegacyPlanParentRow> findLegacyPlanParents(
            @Param("planKeys") Collection<String> planKeys,
            @Param("scopeKinds") Collection<EntitlementScopeKind> scopeKinds,
            @Param("productKind") BillingProductKind productKind,
            @Param("activeStatus") BillingPriceVersionStatus activeStatus,
            @Param("retiredStatus") BillingPriceVersionStatus retiredStatus,
            @Param("scheduledStatus") BillingPriceVersionStatus scheduledStatus,
            @Param("at") Instant at);

    /** planKeyはpriceのraw値ではなく、DB JOINから得たPlan保存値。 */
    interface LegacyPlanDueKey {
        String getPlanKey();
        EntitlementScopeKind getScopeKind();
    }

    /** revisionはLEFT JOINで価格が無い時だけnull。価格Entityのraw keyは変更しない。 */
    interface LegacyPlanParentRow {
        String getPlanKey();
        BillingPriceVersionEntity getRevision();
    }

}
