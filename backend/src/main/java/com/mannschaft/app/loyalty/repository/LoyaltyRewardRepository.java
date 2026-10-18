package com.mannschaft.app.loyalty.repository;

import com.mannschaft.app.loyalty.entity.LoyaltyRewardEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** チーム境界を条件に含めるポイント台帳Repository。 */
public interface LoyaltyRewardRepository extends JpaRepository<LoyaltyRewardEntity, UUID> {

    List<LoyaltyRewardEntity> findByTeamIdOrderById(Long teamId);
    Optional<LoyaltyRewardEntity> findByIdAndTeamId(UUID id, Long teamId);
    long countByTeamId(Long teamId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from LoyaltyRewardEntity r where r.teamId = :teamId and r.id = :id")
    Optional<LoyaltyRewardEntity> lockShared(Long teamId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LoyaltyRewardEntity r where r.teamId = :teamId and r.id = :id")
    Optional<LoyaltyRewardEntity> lockExclusive(Long teamId, UUID id);
}
