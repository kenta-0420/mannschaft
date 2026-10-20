package com.mannschaft.app.loyalty.repository;

import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** チーム境界を条件に含めるポイント台帳Repository。 */
public interface LoyaltyRuleRepository extends JpaRepository<LoyaltyRuleEntity, UUID> {

    Optional<LoyaltyRuleEntity> findByTeamId(Long teamId);
    Optional<LoyaltyRuleEntity> findByIdAndTeamId(UUID id, Long teamId);
    List<LoyaltyRuleEntity> findByTeamIdIn(Collection<Long> teamIds);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from LoyaltyRuleEntity r where r.teamId = :teamId and r.id = :id")
    Optional<LoyaltyRuleEntity> lockShared(Long teamId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LoyaltyRuleEntity r where r.teamId = :teamId and r.id = :id")
    Optional<LoyaltyRuleEntity> lockExclusive(Long teamId, UUID id);
}
