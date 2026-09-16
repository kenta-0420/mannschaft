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

/** {@link BillingPriceBandVersionEntity} の永続化・revision 配下 band 検索。 */
public interface BillingPriceBandVersionRepository extends JpaRepository<BillingPriceBandVersionEntity, UUID> {

    Optional<BillingPriceBandVersionEntity> findByIdAndDeletedAtIsNull(UUID id);

    List<BillingPriceBandVersionEntity> findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(UUID priceVersionId);

    /** 指定時点・人数に有効な販売候補を取得する。 */
    @Query("SELECT b FROM BillingPriceBandVersionEntity b "
            + "WHERE b.productKind = :productKind AND b.productKey = :productKey "
            + "AND b.scopeKind = :scopeKind AND b.status IN :statuses AND b.deletedAt IS NULL "
            + "AND b.effectiveFrom <= :at AND (b.effectiveUntil IS NULL OR :at < b.effectiveUntil) "
            + "AND b.minMembers <= :memberCount AND (b.maxMembers IS NULL OR :memberCount <= b.maxMembers) "
            + "ORDER BY b.bandNo ASC")
    List<BillingPriceBandVersionEntity> findEffectiveCandidates(
            @Param("productKind") BillingProductKind productKind,
            @Param("productKey") String productKey,
            @Param("scopeKind") EntitlementScopeKind scopeKind,
            @Param("statuses") Collection<BillingPriceVersionStatus> statuses,
            @Param("at") Instant at,
            @Param("memberCount") int memberCount);

    /** revision の全 band を悲観ロックして取得する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BillingPriceBandVersionEntity b "
            + "WHERE b.priceVersionId = :priceVersionId AND b.deletedAt IS NULL ORDER BY b.bandNo ASC")
    List<BillingPriceBandVersionEntity> findAllByPriceVersionIdForUpdate(
            @Param("priceVersionId") UUID priceVersionId);
}
