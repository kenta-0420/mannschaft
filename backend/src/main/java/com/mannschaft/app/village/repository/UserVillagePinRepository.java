package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.UserVillagePinEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 30日後の強消去専用。対象本人のピンを全村分まとめて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM user_village_pins WHERE user_id = :userId", nativeQuery = true)
    int deleteAllByUserId(@Param("userId") Long userId);
}
