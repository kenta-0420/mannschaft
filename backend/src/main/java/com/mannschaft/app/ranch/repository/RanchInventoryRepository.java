package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchInventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
    List<RanchInventoryEntity> findByUserIdOrderByAwardedAtDescIdDesc(Long userId);

    @Query(value = "SELECT * FROM ranch_collectible_inventory WHERE user_id = :userId "
            + "AND (:cursorAt IS NULL OR awarded_at < :cursorAt "
            + "OR (awarded_at = :cursorAt AND id < :cursorId)) "
            + "ORDER BY awarded_at DESC, id DESC LIMIT :limitPlusOne", nativeQuery = true)
    List<RanchInventoryEntity> pageForUser(@Param("userId") Long userId,
                                            @Param("cursorAt") Instant cursorAt,
                                            @Param("cursorId") UUID cursorId,
                                            @Param("limitPlusOne") int limitPlusOne);
}
