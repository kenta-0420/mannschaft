package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchInventoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 所有user IDをすべての私的置物検索へ含める。 */
public interface RanchInventoryRepository extends JpaRepository<RanchInventoryEntity, UUID> {
    Optional<RanchInventoryEntity> findByUserIdAndId(Long userId, UUID id);
    Optional<RanchInventoryEntity> findByUserIdAndSkuKey(Long userId, String skuKey);
    Optional<RanchInventoryEntity> findByUserIdAndAcquisitionKindAndAcquisitionKey(
            Long userId, String acquisitionKind, byte[] acquisitionKey);
    Optional<RanchInventoryEntity> findByUserIdAndIdAndRevokedFalse(Long userId, UUID id);
    List<RanchInventoryEntity> findByUserIdAndIdInAndRevokedFalse(
            Long userId, Collection<UUID> ids);
    List<RanchInventoryEntity> findByUserIdOrderByAwardedAtDescIdDesc(Long userId);

    @Query("SELECT DISTINCT item.skuKey FROM RanchInventoryEntity item "
            + "WHERE item.userId = :userId AND item.acquisitionKind = 'SHOP' "
            + "AND item.revoked = false AND item.skuKey IN :skuKeys")
    List<String> ownedShopSkuKeys(@Param("userId") Long userId,
                                  @Param("skuKeys") Collection<String> skuKeys);

    @Query("SELECT item FROM RanchInventoryEntity item WHERE item.userId = :userId "
            + "AND (:cursorAt IS NULL OR item.awardedAt < :cursorAt "
            + "OR (item.awardedAt = :cursorAt AND item.id < :cursorId)) "
            + "ORDER BY item.awardedAt DESC, item.id DESC")
    List<RanchInventoryEntity> pageForUser(@Param("userId") Long userId,
                                            @Param("cursorAt") Instant cursorAt,
                                            @Param("cursorId") UUID cursorId,
                                            Pageable page);
}
