package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchRoomPlacementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 本人user IDをすべての枠検索条件に含める。 */
public interface RanchRoomPlacementRepository extends JpaRepository<RanchRoomPlacementEntity, UUID> {
    List<RanchRoomPlacementEntity> findByUserIdOrderBySlotKey(Long userId);
    Optional<RanchRoomPlacementEntity> findByUserIdAndSlotKey(Long userId, String slotKey);
    List<RanchRoomPlacementEntity> findByUserIdAndInventoryId(Long userId, UUID inventoryId);
}
