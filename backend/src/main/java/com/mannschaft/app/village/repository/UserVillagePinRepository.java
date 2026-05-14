package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.UserVillagePinEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * お気に入り村ピン留めリポジトリ（F17.1 Phase 1）。
 */
public interface UserVillagePinRepository extends JpaRepository<UserVillagePinEntity, UUID> {

    List<UserVillagePinEntity> findByUserIdOrderBySortOrderAsc(Long userId);

    Optional<UserVillagePinEntity> findByUserIdAndVillageId(Long userId, UUID villageId);

    long countByUserId(Long userId);

    void deleteByUserIdAndVillageId(Long userId, UUID villageId);
}
